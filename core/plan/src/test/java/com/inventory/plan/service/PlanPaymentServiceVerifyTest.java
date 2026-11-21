package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.audit.AuditSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.domain.model.OrderPhase;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.payment.PaymentGatewayPort;
import com.inventory.plan.payment.PaymentGatewayResolver;
import com.inventory.plan.payment.dto.VerifyPaymentResult;
import com.inventory.plan.rest.dto.request.VerifyPlanPaymentRequest;
import com.inventory.plan.rest.dto.response.PlanOrderStatusResponse;
import com.inventory.plan.rest.dto.response.VerifyPlanPaymentResponse;
import com.inventory.plan.service.order.OrderFulfilmentService;
import com.inventory.plan.service.order.OrderReservations;
import com.inventory.plan.service.order.PaymentOrderStateService;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.validation.PlanValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** Verify reports success only for a fulfilled order; cancel frees an unpaid order; second captures are audited. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanPaymentServiceVerifyTest {

  private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

  @Mock private PlanRepository planRepository;
  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private PlanValidator planValidator;
  @Mock private PaymentGatewayResolver gatewayResolver;
  @Mock private PaymentGatewayPort gateway;
  @Mock private MetricsWrapper metrics;
  @Mock private PaymentOrderStateService stateService;
  @Mock private OrderReservations reservations;
  @Mock private OrderFulfilmentService fulfilmentService;
  @Mock private AuditService auditService;

  @InjectMocks
  private PlanPaymentService service;

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(gatewayResolver.resolve()).thenReturn(gateway);
    when(gateway.verifyPayment(any())).thenReturn(VerifyPaymentResult.builder().valid(true).build());
  }

  @Test
  void aPaidOrderStillBeingAppliedIsProcessingNotSuccess() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_PAYMENT_PENDING, null)));
    when(stateService.markPaid(eq("order-1"), eq("pay_1"), any(), eq(NOW)))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_PAID, "pay_1")));
    when(fulfilmentService.fulfil("order-1")).thenReturn(order(PlanPaymentConstants.STATUS_FULFILLING, "pay_1"));

    VerifyPlanPaymentResponse response = service.verifyPayment("shop-1", verifyRequest("pay_1"));

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getPhase()).isEqualTo(OrderPhase.PROCESSING);
  }

  @Test
  void aFulfilledOrderIsSuccess() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_PAID, "pay_1")));
    when(fulfilmentService.fulfil("order-1")).thenReturn(order(PlanPaymentConstants.STATUS_FULFILLED, "pay_1"));

    VerifyPlanPaymentResponse response = service.verifyPayment("shop-1", verifyRequest("pay_1"));

    assertThat(response.isSuccess()).isTrue();
    assertThat(response.getPhase()).isEqualTo(OrderPhase.FULFILLED);
    verify(gateway, never()).verifyPayment(any());
  }

  @Test
  void aSecondPaymentOnAPaidOrderIsAuditedForRefund() {
    PlanPaymentOrder paid = order(PlanPaymentConstants.STATUS_FULFILLED, "pay_1");
    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(paid));
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(paid));
    when(stateService.markPaid(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(fulfilmentService.fulfil("order-1")).thenReturn(paid);

    service.verifyPayment("shop-1", verifyRequest("pay_2"));

    verify(gateway).verifyPayment(any());
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(auditService).record(entry.capture());
    assertThat(entry.getValue().getAction()).isEqualTo(PlanPaymentConstants.AUDIT_DUPLICATE_CAPTURE);
    assertThat(entry.getValue().getSource()).isEqualTo(AuditSource.SYSTEM);
    assertThat(entry.getValue().getAfter()).containsEntry("providerPaymentId", "pay_2");
  }

  @Test
  void cancellingAnUnpaidOrderEndsItAndReleasesItsHolds() {
    PlanPaymentOrder open = order(PlanPaymentConstants.STATUS_PAYMENT_PENDING, null);
    when(orderRepository.findByIdAndShopId("order-1", "shop-1")).thenReturn(Optional.of(open));
    when(stateService.endUnpaid(eq("order-1"), eq(PlanPaymentConstants.STATUS_CANCELLED), anyString(), eq(NOW)))
        .thenReturn(true);
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_CANCELLED, null)));

    PlanOrderStatusResponse response = service.cancelOrder("shop-1", "order-1");

    verify(reservations).releaseEnded(open, NOW);
    assertThat(response.getStatus()).isEqualTo(PlanPaymentConstants.STATUS_CANCELLED);
    assertThat(response.getPhase()).isEqualTo(OrderPhase.ENDED);
  }

  @Test
  void cancellingAnEndedOrderAgainChangesNothing() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-1"))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_CANCELLED, null)));

    assertThat(service.cancelOrder("shop-1", "order-1").getStatus()).isEqualTo(PlanPaymentConstants.STATUS_CANCELLED);
    verify(stateService, never()).endUnpaid(any(), any(), any(), any());
    verify(reservations, never()).releaseEnded(any(), any());
  }

  @Test
  void aPaidOrderCannotBeCancelled() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-1"))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_PAID, "pay_1")));

    assertThatThrownBy(() -> service.cancelOrder("shop-1", "order-1"))
        .isInstanceOfSatisfying(BaseException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ORDER_NOT_CANCELLABLE));
  }

  @Test
  void anotherShopsOrderIsNotFound() {
    when(orderRepository.findByIdAndShopId("order-1", "shop-2")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.cancelOrder("shop-2", "order-1"))
        .isInstanceOf(com.inventory.common.exception.ResourceNotFoundException.class);
  }

  private static VerifyPlanPaymentRequest verifyRequest(String paymentId) {
    VerifyPlanPaymentRequest request = new VerifyPlanPaymentRequest();
    request.setOrderId("order-1");
    request.setRazorpayOrderId("rzp_1");
    request.setRazorpayPaymentId(paymentId);
    request.setRazorpaySignature("sig");
    return request;
  }

  private static PlanPaymentOrder order(String status, String paymentId) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setPlanId("plan-1");
    order.setStatus(status);
    order.setProvider(PlanPaymentConstants.PROVIDER_RAZORPAY);
    order.setProviderOrderId("rzp_1");
    order.setProviderPaymentId(paymentId);
    return order;
  }
}
