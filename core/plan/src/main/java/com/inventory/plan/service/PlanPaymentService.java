package com.inventory.plan.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.payment.PaymentGatewayPort;
import com.inventory.plan.payment.PaymentGatewayResolver;
import com.inventory.plan.payment.dto.CreateCheckoutCommand;
import com.inventory.plan.payment.dto.CreateCheckoutResult;
import com.inventory.plan.payment.dto.VerifyPaymentCommand;
import com.inventory.plan.payment.dto.VerifyPaymentResult;
import com.inventory.plan.payment.dto.WebhookHandleCommand;
import com.inventory.plan.payment.dto.WebhookHandleResult;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.VerifyPlanPaymentRequest;
import com.inventory.plan.rest.dto.response.PaymentConfigResponse;
import com.inventory.plan.rest.dto.response.PlanCheckoutResponse;
import com.inventory.plan.rest.dto.response.VerifyPlanPaymentResponse;
import com.inventory.plan.service.order.OrderFulfilmentService;
import com.inventory.plan.service.order.OrderReservations;
import com.inventory.plan.service.order.PaymentOrderStateService;
import com.inventory.plan.service.refund.OrderRefundService;
import com.inventory.plan.utils.CheckoutRequestHash;
import com.inventory.plan.utils.constants.PlanMetricsConstants;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.utils.constants.PricingConstants;
import com.inventory.plan.validation.PlanValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Plan checkout on top of the order state machine: CREATED → PAYMENT_PENDING → PAID → FULFILLING →
 * FULFILLED, with every transition a conditional write so retries, webhooks and parallel requests
 * converge on one outcome.
 */
@Service
@Slf4j
public class PlanPaymentService {

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private PlanPaymentOrderRepository planPaymentOrderRepository;

  @Autowired
  private PlanMapper planMapper;

  @Autowired
  private PlanValidator planValidator;

  @Autowired
  private PaymentGatewayResolver paymentGatewayResolver;

  @Autowired
  private MetricsWrapper metrics;

  @Autowired
  private OrderPricingService orderPricingService;

  @Autowired
  private PaymentOrderStateService stateService;

  @Autowired
  private OrderReservations reservations;

  @Autowired
  private OrderFulfilmentService fulfilmentService;

  @Autowired
  private OrderRefundService orderRefundService;

  @Value("${plan.checkout.order-ttl-minutes:30}")
  long orderTtlMinutes = 30;

  Clock clock = Clock.systemUTC();

  public PaymentConfigResponse getPaymentConfig() {
    return PaymentConfigResponse.builder()
        .provider(paymentGatewayResolver.activeProviderId())
        .publicKey(paymentGatewayResolver.activePublicKey())
        .build();
  }

  /**
   * Creates (or, for a repeated {@code Idempotency-Key}, returns) the order for a cart. The same key
   * with a different cart is rejected rather than silently charging for the first cart.
   */
  public PlanCheckoutResponse createCheckout(String shopId, CreatePlanCheckoutRequest request, String idempotencyKey) {
    planValidator.validateCreateCheckoutRequest(shopId, request, idempotencyKey);
    Plan plan = resolvePlan(request);
    if (request.getDurationMonths() == null) {
      request.setDurationMonths(PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS);
    }
    String requestHash = CheckoutRequestHash.of(shopId, planKey(plan), request);
    String key = idempotencyKey != null ? idempotencyKey.trim() : null;

    // Replays are matched before pricing: the original order already holds its voucher slots, so
    // re-checking them would reject the shop's own retry.
    if (key != null) {
      Optional<PlanPaymentOrder> existing = planPaymentOrderRepository.findByShopIdAndIdempotencyKey(shopId, key);
      if (existing.isPresent()) {
        return replay(existing.get(), requestHash);
      }
    }
    PricedCart cart = orderPricingService.price(plan, request, shopId);

    boolean walletPaysAll = cart.grandTotal().signum() == 0;
    PaymentGatewayPort gateway = walletPaysAll ? null : paymentGatewayResolver.resolve();
    String provider = walletPaysAll ? PlanPaymentConstants.PROVIDER_WALLET : gateway.providerId();
    PlanPaymentOrder order;
    try {
      order = planPaymentOrderRepository.insert(newOrder(shopId, cart, provider, key, requestHash));
    } catch (DuplicateKeyException e) {
      PlanPaymentOrder raced = planPaymentOrderRepository.findByShopIdAndIdempotencyKey(shopId, key)
          .orElseThrow(() -> e);
      return replay(raced, requestHash);
    }

    try {
      reservations.reserve(order, cart);
    } catch (RuntimeException e) {
      stateService.endUnpaid(order.getId(), PlanPaymentConstants.STATUS_CANCELLED, e.getMessage(), now());
      throw e;
    }

    if (walletPaysAll) {
      return checkoutResponse(payFromWallet(order));
    }
    PlanCheckoutResponse response = openWithGateway(order, gateway);
    recordPayment("checkout", "success");
    return response;
  }

  public VerifyPlanPaymentResponse verifyPayment(String shopId, VerifyPlanPaymentRequest request) {
    planValidator.validateVerifyPaymentRequest(request);

    PlanPaymentOrder order = planPaymentOrderRepository.findByIdAndShopId(request.getOrderId(), shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan payment order", "id", request.getOrderId()));

    if (PlanPaymentConstants.PAID_STATUSES.contains(order.getStatus())) {
      return verifyResponse(fulfilmentService.fulfil(order.getId()));
    }

    if (!request.getRazorpayOrderId().equals(order.getProviderOrderId())) {
      throw new ValidationException("Payment order mismatch");
    }

    PaymentGatewayPort gateway = paymentGatewayResolver.resolve();
    VerifyPaymentResult verified = gateway.verifyPayment(VerifyPaymentCommand.builder()
        .providerOrderId(request.getRazorpayOrderId())
        .providerPaymentId(request.getRazorpayPaymentId())
        .signature(request.getRazorpaySignature())
        .build());

    if (!verified.isValid()) {
      if (stateService.endUnpaid(order.getId(), PlanPaymentConstants.STATUS_PAYMENT_FAILED,
          "Payment signature verification failed", now())) {
        reservations.release(order);
      }
      recordPayment("verify", "error");
      throw new ValidationException("Payment signature verification failed");
    }

    PlanPaymentOrder fulfilled = payAndFulfil(order.getId(), request.getRazorpayPaymentId(), verified.getPaymentMethod());
    recordPayment("verify", "success");
    return verifyResponse(fulfilled);
  }

  public void handleProviderWebhook(String provider, String rawBody, Map<String, String> headers) {
    if (!paymentGatewayResolver.activeProviderId().equals(provider)) {
      log.warn("Ignoring webhook for inactive provider {}", provider);
      return;
    }

    PaymentGatewayPort gateway = paymentGatewayResolver.resolve();
    WebhookHandleResult result = gateway.handleWebhook(WebhookHandleCommand.builder()
        .rawBody(rawBody)
        .headers(headers != null ? headers : new HashMap<>())
        .build());

    if (result.isProcessed()
        && result.effectiveEventType() != WebhookHandleResult.EventType.PAYMENT_CAPTURED) {
      orderRefundService.onGatewayEvent(provider, result);
      return;
    }
    if (!result.isProcessed()
        || !StringUtils.hasText(result.getProviderOrderId())
        || !StringUtils.hasText(result.getProviderPaymentId())) {
      return;
    }

    PlanPaymentOrder order = planPaymentOrderRepository
        .findByProviderAndProviderOrderId(provider, result.getProviderOrderId())
        .orElse(null);
    if (order == null) {
      log.warn("No plan payment order for provider order {}", result.getProviderOrderId());
      return;
    }

    payAndFulfil(order.getId(), result.getProviderPaymentId(), result.getPaymentMethod());
    metrics.record(
        PlanMetricsConstants.WEBHOOKS_TOTAL,
        1,
        "module",
        PlanMetricsConstants.MODULE,
        "outcome",
        "success");
  }

  /** Records the payment (a no-op if already recorded) and runs fulfilment. */
  private PlanPaymentOrder payAndFulfil(String orderId, String providerPaymentId, String paymentMethod) {
    Optional<PlanPaymentOrder> paid = stateService.markPaid(orderId, providerPaymentId, paymentMethod, now());
    paid.filter(PlanPaymentOrder::isLatePayment).ifPresent(order ->
        log.warn("Order {} for shop {} was paid after it ended; reacquiring its reservations",
            order.getId(), order.getShopId()));
    return fulfilmentService.fulfil(orderId);
  }

  /** An order with nothing left to charge: the wallet reservation is the payment. */
  private PlanPaymentOrder payFromWallet(PlanPaymentOrder order) {
    PlanPaymentOrder fulfilled = payAndFulfil(order.getId(), null, PlanPaymentConstants.PAYMENT_METHOD_WALLET);
    recordPayment("checkout", "wallet");
    return fulfilled;
  }

  private PlanCheckoutResponse replay(PlanPaymentOrder order, String requestHash) {
    if (order.getRequestHash() != null && !order.getRequestHash().equals(requestHash)) {
      throw new BaseException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "This " + PlanPaymentConstants.IDEMPOTENCY_KEY_HEADER + " was already used for a different cart");
    }
    String status = order.getStatus();
    if (PlanPaymentConstants.PROVIDER_WALLET.equals(order.getProvider())
        && PlanPaymentConstants.OPEN_STATUSES.contains(status)) {
      return checkoutResponse(payFromWallet(order));
    }
    if (PlanPaymentConstants.STATUS_CREATED.equals(status) && order.getProviderOrderId() == null) {
      return openWithGateway(order, paymentGatewayResolver.resolve());
    }
    if (PlanPaymentConstants.UNPAID_ENDED_STATUSES.contains(status)) {
      throw new ValidationException("This checkout has ended (" + status + "); start a new checkout");
    }
    return checkoutResponse(order);
  }

  /** Creates the gateway order and moves the order to PAYMENT_PENDING. */
  private PlanCheckoutResponse openWithGateway(PlanPaymentOrder order, PaymentGatewayPort gateway) {
    CreateCheckoutResult checkout;
    try {
      checkout = gateway.createCheckout(CreateCheckoutCommand.builder()
          .internalOrderId(order.getId())
          .shopId(order.getShopId())
          .planId(order.getPlanId())
          .planName(order.getPlanName())
          .amount(order.getAmount())
          .currency(order.getCurrency())
          .durationMonths(order.getDurationMonths())
          .build());
    } catch (RuntimeException e) {
      if (stateService.endUnpaid(order.getId(), PlanPaymentConstants.STATUS_PAYMENT_FAILED,
          "Gateway order could not be created: " + e.getMessage(), now())) {
        reservations.release(order);
      }
      recordPayment("checkout", "error");
      throw e;
    }

    PlanPaymentOrder pending = stateService.attachProviderOrder(order.getId(), checkout.getProviderOrderId(), now())
        .orElseGet(() -> planPaymentOrderRepository.findById(order.getId()).orElse(order));
    return checkoutResponse(pending);
  }

  private PlanCheckoutResponse checkoutResponse(PlanPaymentOrder order) {
    PlanCheckoutResponse.PlanCheckoutResponseBuilder response = PlanCheckoutResponse.builder()
        .orderId(order.getId())
        .status(order.getStatus())
        .provider(order.getProvider())
        .amount(order.getAmount())
        .currency(order.getCurrency())
        .planName(order.getPlanName())
        .items(order.getItems() != null
            ? order.getItems().stream().map(OrderPricingService::toQuoteItem).toList()
            : null)
        .subtotal(order.getSubtotal())
        .discountTotal(order.getDiscountTotal())
        .walletCredit(order.getWalletCredit())
        .expiresAt(order.getExpiresAt());

    if (PlanPaymentConstants.PROVIDER_RAZORPAY.equals(order.getProvider())) {
      response.razorpay(PlanCheckoutResponse.RazorpayPayload.builder()
          .keyId(paymentGatewayResolver.activePublicKey())
          .orderId(order.getProviderOrderId())
          .build());
    }
    return response.build();
  }

  private VerifyPlanPaymentResponse verifyResponse(PlanPaymentOrder order) {
    Plan plan = planRepository.findById(order.getPlanId()).orElse(null);
    return VerifyPlanPaymentResponse.builder()
        .success(!PlanPaymentConstants.STATUS_PAYMENT_FAILED.equals(order.getStatus()))
        .orderId(order.getId())
        .status(order.getStatus())
        .plan(plan != null ? planMapper.toResponse(plan) : null)
        .build();
  }

  private PlanPaymentOrder newOrder(String shopId, PricedCart cart, String provider, String key, String requestHash) {
    Instant now = now();
    Plan plan = cart.plan();
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setShopId(shopId);
    order.setPlanId(plan.getId());
    order.setPlanCode(plan.getCode());
    order.setPlanName(plan.getPlanName());
    order.setAmount(cart.grandTotal());
    order.setCurrency(PlanPaymentConstants.CURRENCY_INR);
    order.setDurationMonths(cart.durationMonths());
    order.setItems(cart.items());
    order.setSubtotal(cart.subtotal());
    order.setDiscountTotal(cart.discountTotal());
    order.setWalletCredit(cart.walletCredit());
    order.setPricingVersion(PricingConstants.PRICING_VERSION);
    order.setIdempotencyKey(key);
    order.setRequestHash(requestHash);
    order.setProvider(provider);
    order.setStatus(PlanPaymentConstants.STATUS_CREATED);
    order.setExpiresAt(now.plus(Duration.ofMinutes(orderTtlMinutes)));
    order.setCreatedAt(now);
    order.setUpdatedAt(now);
    return order;
  }

  private Plan resolvePlan(CreatePlanCheckoutRequest request) {
    if (StringUtils.hasText(request.getPlanCode())) {
      String planCode = request.getPlanCode().trim().toUpperCase(Locale.ROOT);
      return planRepository.findByCode(planCode)
          .filter(EffectivePlanResolver::isActive)
          .orElseThrow(() -> new ResourceNotFoundException("Plan", "code", planCode));
    }
    return planRepository.findById(request.getPlanId())
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", request.getPlanId()));
  }

  private static String planKey(Plan plan) {
    return plan.getCode() != null ? plan.getCode() : "id:" + plan.getId();
  }

  private void recordPayment(String operation, String outcome) {
    metrics.record(
        PlanMetricsConstants.PAYMENTS_TOTAL,
        1,
        "module",
        PlanMetricsConstants.MODULE,
        "operation",
        operation,
        "outcome",
        outcome);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MILLIS);
  }
}
