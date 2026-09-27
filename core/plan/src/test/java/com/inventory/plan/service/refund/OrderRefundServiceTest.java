package com.inventory.plan.service.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.audit.AuditEntry;
import com.inventory.common.audit.AuditService;
import com.inventory.common.exception.BaseException;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderRefundServiceTest {

  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private PaymentOrderStateService stateService;
  @Mock private PaymentGatewayResolver gatewayResolver;
  @Mock private PaymentGatewayPort gateway;
  @Mock private AuditService auditService;
  @Mock private OrderRefundStep planStep;
  @Mock private OrderRefundStep rewardStep;

  @InjectMocks
  private OrderRefundService service;

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "steps", List.of(planStep, rewardStep));
    lenient().when(gateway.providerId()).thenReturn("razorpay");
    lenient().when(gatewayResolver.resolve()).thenReturn(gateway);
  }

  private static PlanPaymentOrder order(String status, String refunded) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setStatus(status);
    order.setProvider("razorpay");
    order.setProviderPaymentId("pay_1");
    order.setAmount(new BigDecimal("9999.00"));
    order.setRefundedAmount(refunded == null ? null : new BigDecimal(refunded));
    return order;
  }

  private static WebhookHandleResult refundEvent(String refundId, String amount) {
    return WebhookHandleResult.builder().processed(true).eventType(WebhookHandleResult.EventType.REFUND_PROCESSED)
        .providerPaymentId("pay_1").providerRefundId(refundId).amount(new BigDecimal(amount)).build();
  }

  @Test
  void anAdminRefundIssuesTheGatewayRefundThenReversesEveryStep() {
    PlanPaymentOrder fulfilled = order(PlanPaymentConstants.STATUS_FULFILLED, null);
    PlanPaymentOrder refunded = order(PlanPaymentConstants.STATUS_REFUNDED, "9999.00");
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(fulfilled));
    when(gateway.refund(eq("pay_1"), eq(new BigDecimal("9999.00")), anyMap())).thenReturn("rfnd_1");
    when(stateService.markRefunded(eq("order-1"), eq(RefundSource.ADMIN), eq("Customer asked"), eq("admin-1"), any()))
        .thenReturn(Optional.of(refunded));

    assertThat(service.refundByAdmin("order-1", " Customer asked ", true, "admin-1")).isSameAs(refunded);

    verify(stateService).recordGatewayRefund(eq("order-1"), eq("rfnd_1"), eq(new BigDecimal("9999.00")), any());
    InOrder steps = inOrder(planStep, rewardStep);
    steps.verify(planStep).reverse(refunded);
    steps.verify(rewardStep).reverse(refunded);
    verify(auditService).record(any(AuditEntry.class));
  }

  @Test
  void anAdminRefundWithoutGatewayRefundOnlyReverses() {
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_FULFILLED, null)));
    when(stateService.markRefunded(any(), any(), any(), any(), any()))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_REFUNDED, null)));

    service.refundByAdmin("order-1", "Refunded on dashboard", false, "admin-1");

    verify(gateway, never()).refund(anyString(), any(), anyMap());
    verify(planStep).reverse(any());
  }

  @Test
  void anAdminRefundNeedsAReasonAndARefundableOrder() {
    assertThatThrownBy(() -> service.refundByAdmin("order-1", " ", true, "admin-1"))
        .isInstanceOf(ValidationException.class);

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_FULFILLING, null)));
    assertThatThrownBy(() -> service.refundByAdmin("order-1", "why", true, "admin-1"))
        .isInstanceOf(BaseException.class)
        .hasMessageContaining("being fulfilled");
    verify(planStep, never()).reverse(any());
  }

  @Test
  void retryingARefundedOrderFinishesTheStepsWithoutRefundingAgain() {
    PlanPaymentOrder refunded = order(PlanPaymentConstants.STATUS_REFUNDED, "9999.00");
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(refunded));
    when(stateService.markRefunded(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

    service.refundByAdmin("order-1", "retry", true, "admin-1");

    verify(gateway, never()).refund(anyString(), any(), anyMap());
    verify(auditService, never()).record(any());
    verify(planStep).reverse(refunded);
  }

  @Test
  void aPartialGatewayRefundIsCountedButKeepsTheGrants() {
    PlanPaymentOrder partlyRefunded = order(PlanPaymentConstants.STATUS_FULFILLED, "2000.00");
    when(orderRepository.findByProviderAndProviderPaymentId("razorpay", "pay_1")).thenReturn(Optional.of(order(
        PlanPaymentConstants.STATUS_FULFILLED, null)));
    when(stateService.recordGatewayRefund(eq("order-1"), eq("rfnd_1"), eq(new BigDecimal("2000.00")), any()))
        .thenReturn(Optional.of(partlyRefunded));

    assertThat(service.onGatewayEvent("razorpay", refundEvent("rfnd_1", "2000.00"))).contains(partlyRefunded);

    verify(stateService, never()).markRefunded(any(), any(), any(), any(), any());
    verify(auditService).record(any(AuditEntry.class));
  }

  @Test
  void gatewayRefundsCoveringTheOrderReverseIt() {
    when(orderRepository.findByProviderAndProviderPaymentId("razorpay", "pay_1")).thenReturn(Optional.of(order(
        PlanPaymentConstants.STATUS_FULFILLED, "2000.00")));
    when(stateService.recordGatewayRefund(any(), eq("rfnd_2"), any(), any()))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_FULFILLED, "9999.00")));
    when(stateService.markRefunded(eq("order-1"), eq(RefundSource.GATEWAY_REFUND), anyString(), isNull(), any()))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_REFUNDED, "9999.00")));

    service.onGatewayEvent("razorpay", refundEvent("rfnd_2", "7999.00"));

    verify(planStep).reverse(any());
    verify(rewardStep).reverse(any());
  }

  @Test
  void aLostDisputeIsAFullRefund() {
    when(orderRepository.findByProviderAndProviderPaymentId("razorpay", "pay_1")).thenReturn(Optional.of(order(
        PlanPaymentConstants.STATUS_FULFILLED, null)));
    when(stateService.markRefunded(eq("order-1"), eq(RefundSource.GATEWAY_DISPUTE), anyString(), isNull(), any()))
        .thenReturn(Optional.of(order(PlanPaymentConstants.STATUS_REFUNDED, null)));

    service.onGatewayEvent("razorpay", WebhookHandleResult.builder().processed(true)
        .eventType(WebhookHandleResult.EventType.DISPUTE_LOST).providerPaymentId("pay_1")
        .providerRefundId("disp_1").amount(new BigDecimal("9999.00")).build());

    verify(stateService, never()).recordGatewayRefund(any(), any(), any(), any());
    verify(planStep).reverse(any());
  }

  @Test
  void aRefundForAnUnknownPaymentIsIgnored() {
    when(orderRepository.findByProviderAndProviderPaymentId("razorpay", "pay_1")).thenReturn(Optional.empty());

    assertThat(service.onGatewayEvent("razorpay", refundEvent("rfnd_1", "10.00"))).isEmpty();
    verify(planStep, never()).reverse(any());
  }
}
