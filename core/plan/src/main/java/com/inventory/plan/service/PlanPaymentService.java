package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
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
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.VerifyPlanPaymentRequest;
import com.inventory.plan.rest.dto.response.PaymentConfigResponse;
import com.inventory.plan.rest.dto.response.PlanCheckoutResponse;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.VerifyPlanPaymentResponse;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.validation.PlanValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class PlanPaymentService {

  @Autowired
  private PlanRepository planRepository;

  @Autowired
  private PlanPaymentOrderRepository planPaymentOrderRepository;

  @Autowired
  private PlanService planService;

  @Autowired
  private PlanMapper planMapper;

  @Autowired
  private PlanValidator planValidator;

  @Autowired
  private PaymentGatewayResolver paymentGatewayResolver;

  @Transactional(readOnly = true)
  public PaymentConfigResponse getPaymentConfig() {
    return PaymentConfigResponse.builder()
        .provider(paymentGatewayResolver.activeProviderId())
        .publicKey(paymentGatewayResolver.activePublicKey())
        .build();
  }

  @Transactional
  public PlanCheckoutResponse createCheckout(String shopId, CreatePlanCheckoutRequest request) {
    planValidator.validateCreateCheckoutRequest(shopId, request);

    Plan plan = planRepository.findById(request.getPlanId())
        .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", request.getPlanId()));

    int durationMonths = request.getDurationMonths() != null
        ? request.getDurationMonths()
        : PlanPaymentConstants.DEFAULT_CHECKOUT_DURATION_MONTHS;
    BigDecimal amount = planAmount(plan);
    PaymentGatewayPort gateway = paymentGatewayResolver.resolve();

    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setShopId(shopId);
    order.setPlanId(plan.getId());
    order.setPlanName(plan.getPlanName());
    order.setAmount(amount);
    order.setCurrency(PlanPaymentConstants.CURRENCY_INR);
    order.setDurationMonths(durationMonths);
    order.setProvider(gateway.providerId());
    order.setStatus(PlanPaymentConstants.STATUS_CREATED);
    order.setCreatedAt(Instant.now());
    order = planPaymentOrderRepository.save(order);

    CreateCheckoutResult checkout = gateway.createCheckout(CreateCheckoutCommand.builder()
        .internalOrderId(order.getId())
        .shopId(shopId)
        .planId(plan.getId())
        .planName(plan.getPlanName())
        .amount(amount)
        .currency(PlanPaymentConstants.CURRENCY_INR)
        .durationMonths(durationMonths)
        .build());

    order.setProviderOrderId(checkout.getProviderOrderId());
    planPaymentOrderRepository.save(order);

    PlanCheckoutResponse.PlanCheckoutResponseBuilder response = PlanCheckoutResponse.builder()
        .orderId(order.getId())
        .provider(gateway.providerId())
        .amount(amount)
        .currency(PlanPaymentConstants.CURRENCY_INR)
        .planName(plan.getPlanName());

    if (PlanPaymentConstants.PROVIDER_RAZORPAY.equals(gateway.providerId())) {
      response.razorpay(PlanCheckoutResponse.RazorpayPayload.builder()
          .keyId(checkout.getPublicKey())
          .orderId(checkout.getProviderOrderId())
          .build());
    }

    return response.build();
  }

  @Transactional
  public VerifyPlanPaymentResponse verifyPayment(String shopId, VerifyPlanPaymentRequest request) {
    planValidator.validateVerifyPaymentRequest(request);

    PlanPaymentOrder order = planPaymentOrderRepository.findByIdAndShopId(request.getOrderId(), shopId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan payment order", "id", request.getOrderId()));

    if (PlanPaymentConstants.STATUS_FULFILLED.equals(order.getStatus())) {
      Plan plan = planRepository.findById(order.getPlanId()).orElse(null);
      return VerifyPlanPaymentResponse.builder()
          .success(true)
          .orderId(order.getId())
          .plan(plan != null ? planMapper.toResponse(plan) : null)
          .build();
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
      order.setStatus(PlanPaymentConstants.STATUS_FAILED);
      planPaymentOrderRepository.save(order);
      throw new ValidationException("Payment signature verification failed");
    }

    PlanResponse plan = fulfillOrder(order, verified.getPaymentMethod(), request.getRazorpayPaymentId());
    return VerifyPlanPaymentResponse.builder()
        .success(true)
        .orderId(order.getId())
        .plan(plan)
        .build();
  }

  @Transactional
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

    if (PlanPaymentConstants.STATUS_FULFILLED.equals(order.getStatus())) {
      return;
    }

    fulfillOrder(order, result.getPaymentMethod(), result.getProviderPaymentId());
  }

  private PlanResponse fulfillOrder(PlanPaymentOrder order, String paymentMethod, String providerPaymentId) {
    if (!PlanPaymentConstants.STATUS_PAID.equals(order.getStatus())
        && !PlanPaymentConstants.STATUS_CREATED.equals(order.getStatus())) {
      if (PlanPaymentConstants.STATUS_FULFILLED.equals(order.getStatus())) {
        Plan plan = planRepository.findById(order.getPlanId()).orElse(null);
        return plan != null ? planMapper.toResponse(plan) : null;
      }
    }

    order.setProviderPaymentId(providerPaymentId);
    order.setStatus(PlanPaymentConstants.STATUS_PAID);
    order.setPaidAt(Instant.now());
    planPaymentOrderRepository.save(order);

    AssignPlanRequest assignRequest = new AssignPlanRequest();
    assignRequest.setPlanId(order.getPlanId());
    assignRequest.setDurationMonths(order.getDurationMonths());
    assignRequest.setPaymentMethod(paymentMethod);
    assignRequest.setPaymentOrderId(order.getId());
    assignRequest.setProvider(order.getProvider());
    assignRequest.setProviderPaymentId(providerPaymentId);
    assignRequest.setProviderOrderId(order.getProviderOrderId());

    PlanResponse assigned = planService.assignPlan(order.getShopId(), assignRequest);

    order.setStatus(PlanPaymentConstants.STATUS_FULFILLED);
    order.setFulfilledAt(Instant.now());
    planPaymentOrderRepository.save(order);

    log.info("Fulfilled plan payment order {} for shop {}", order.getId(), order.getShopId());
    return assigned;
  }

  private static BigDecimal planAmount(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
  }
}
