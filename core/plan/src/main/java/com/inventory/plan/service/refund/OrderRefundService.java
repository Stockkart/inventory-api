package com.inventory.plan.service.refund;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.RefundSource;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.payment.PaymentGatewayPort;
import com.inventory.plan.payment.PaymentGatewayResolver;
import com.inventory.plan.payment.dto.WebhookHandleResult;
import com.inventory.plan.service.order.PaymentOrderStateService;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The one refund path (r4.6). Gateway refunds, lost disputes and admin refunds all end here: the order
 * moves to REFUNDED, then each {@link OrderRefundStep} undoes part of fulfilment. Vouchers stay
 * redeemed: a refund does not hand the slot back, so a capped voucher cannot be cycled.
 *
 * <p>A partial gateway refund is counted on the order and audited but leaves its grants alone; an
 * operator decides, and can finish it with an admin refund that issues nothing further.
 */
@Slf4j
@Service
public class OrderRefundService {

  static final String TARGET_TYPE = "PLAN_ORDER";

  @Autowired
  private PlanPaymentOrderRepository orderRepository;

  @Autowired
  private PaymentOrderStateService stateService;

  @Autowired
  private List<OrderRefundStep> steps;

  @Autowired
  private PaymentGatewayResolver gatewayResolver;

  @Autowired
  private AuditService auditService;

  Clock clock = Clock.systemUTC();

  /** A refund or lost dispute reported by the gateway's webhook. */
  public Optional<PlanPaymentOrder> onGatewayEvent(String provider, WebhookHandleResult event) {
    Optional<PlanPaymentOrder> found = orderRepository
        .findByProviderAndProviderPaymentId(provider, event.getProviderPaymentId())
        .or(() -> StringUtils.hasText(event.getProviderOrderId())
            ? orderRepository.findByProviderAndProviderOrderId(provider, event.getProviderOrderId())
            : Optional.empty());
    if (found.isEmpty()) {
      log.warn("No plan order for {} payment {}; refund event ignored", provider, event.getProviderPaymentId());
      return Optional.empty();
    }
    PlanPaymentOrder order = found.get();
    if (event.effectiveEventType() == WebhookHandleResult.EventType.DISPUTE_LOST) {
      return Optional.of(reverse(order, RefundSource.GATEWAY_DISPUTE,
          "Chargeback lost (" + event.getProviderRefundId() + ")", null));
    }
    if (!StringUtils.hasText(event.getProviderRefundId()) || event.getAmount() == null) {
      log.warn("Refund event for order {} has no refund id or amount; ignored", order.getId());
      return Optional.empty();
    }
    Optional<PlanPaymentOrder> counted =
        stateService.recordGatewayRefund(order.getId(), event.getProviderRefundId(), event.getAmount(), clock.instant());
    PlanPaymentOrder current = counted.orElseGet(() -> reload(order.getId()));
    if (PlanPaymentConstants.STATUS_REFUNDED.equals(current.getStatus()) || refundedInFull(current)) {
      return Optional.of(reverse(current, RefundSource.GATEWAY_REFUND,
          "Gateway refund " + event.getProviderRefundId(), null));
    }
    if (counted.isPresent()) {
      log.warn("Order {} partly refunded ({} of {}); grants kept, needs an operator",
          order.getId(), current.getRefundedAmount(), current.getAmount());
      audit("PLAN_ORDER_PARTIALLY_REFUNDED", current, null, AuditSource.SYSTEM,
          "Gateway refund " + event.getProviderRefundId() + " of " + event.getAmount());
    }
    return Optional.of(current);
  }

  /**
   * A platform admin refunds an order. With {@code issueGatewayRefund}, whatever the gateway has not
   * refunded yet is refunded there first; without it, the money is taken to be settled already.
   */
  public PlanPaymentOrder refundByAdmin(String orderId, String reason, boolean issueGatewayRefund, String actorUserId) {
    if (!StringUtils.hasText(reason)) {
      throw new ValidationException("A reason is required to refund an order");
    }
    PlanPaymentOrder order = reload(orderId);
    boolean refundable = PlanPaymentConstants.REFUNDABLE_STATUSES.contains(order.getStatus());
    if (!refundable && !PlanPaymentConstants.STATUS_REFUNDED.equals(order.getStatus())) {
      throw notRefundable(order);
    }
    BigDecimal outstanding = nz(order.getAmount()).subtract(nz(order.getRefundedAmount()));
    if (refundable && issueGatewayRefund && paidThroughGateway(order) && outstanding.signum() > 0) {
      PaymentGatewayPort gateway = gatewayResolver.resolve();
      if (!gateway.providerId().equals(order.getProvider())) {
        throw new ValidationException("Order was paid through " + order.getProvider()
            + ", which is not the active gateway. Refund it there, then retry without issuing a gateway refund.");
      }
      String refundId = gateway.refund(order.getProviderPaymentId(), outstanding,
          Map.of("internalOrderId", order.getId(), "reason", reason));
      stateService.recordGatewayRefund(order.getId(), refundId, outstanding, clock.instant());
    }
    return reverse(order, RefundSource.ADMIN, reason.trim(), actorUserId);
  }

  /**
   * Marks the order REFUNDED (once) and runs every reversal step. Re-running on an order already
   * REFUNDED finishes steps a failed earlier run left undone.
   */
  PlanPaymentOrder reverse(PlanPaymentOrder order, RefundSource source, String reason, String actorUserId) {
    Optional<PlanPaymentOrder> marked =
        stateService.markRefunded(order.getId(), source, reason, actorUserId, clock.instant());
    PlanPaymentOrder refunded;
    if (marked.isPresent()) {
      refunded = marked.get();
      audit("PLAN_ORDER_REFUNDED", refunded, actorUserId,
          source == RefundSource.ADMIN ? AuditSource.ADMIN_UI : AuditSource.SYSTEM, reason);
      log.info("Order {} for shop {} refunded ({}): {}", refunded.getId(), refunded.getShopId(), source, reason);
    } else {
      refunded = reload(order.getId());
      if (!PlanPaymentConstants.STATUS_REFUNDED.equals(refunded.getStatus())) {
        throw notRefundable(refunded);
      }
    }
    for (OrderRefundStep step : steps) {
      step.reverse(refunded);
    }
    return refunded;
  }

  private PlanPaymentOrder reload(String orderId) {
    return orderRepository.findById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Plan order", "id", orderId));
  }

  private static boolean refundedInFull(PlanPaymentOrder order) {
    return nz(order.getAmount()).signum() > 0 && nz(order.getRefundedAmount()).compareTo(nz(order.getAmount())) >= 0;
  }

  private static boolean paidThroughGateway(PlanPaymentOrder order) {
    return !PlanPaymentConstants.PROVIDER_WALLET.equals(order.getProvider())
        && StringUtils.hasText(order.getProviderPaymentId());
  }

  private static BaseException notRefundable(PlanPaymentOrder order) {
    String hint = PlanPaymentConstants.STATUS_FULFILLING.equals(order.getStatus())
        ? " while it is being fulfilled; retry shortly" : "";
    return new BaseException(ErrorCode.ORDER_NOT_REFUNDABLE,
        "Order " + order.getId() + " is " + order.getStatus() + " and cannot be refunded" + hint);
  }

  private void audit(String action, PlanPaymentOrder order, String actorUserId, AuditSource source, String reason) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("shopId", order.getShopId());
    after.put("status", order.getStatus());
    after.put("amount", order.getAmount());
    after.put("walletCredit", order.getWalletCredit());
    after.put("refundedAmount", order.getRefundedAmount());
    after.put("refundSource", order.getRefundSource());
    auditService.record(AuditEntry.builder()
        .actorUserId(actorUserId)
        .action(action)
        .targetType(TARGET_TYPE)
        .targetId(order.getId())
        .after(after)
        .reason(reason)
        .source(source)
        .build());
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
