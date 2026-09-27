package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.domain.model.OrderLine;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.payment.PaymentGatewayPort;
import com.inventory.plan.payment.PaymentGatewayResolver;
import com.inventory.plan.payment.dto.CreateCheckoutResult;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.response.PlanCheckoutResponse;
import com.inventory.plan.service.order.OrderFulfilmentService;
import com.inventory.plan.service.order.OrderReservations;
import com.inventory.plan.service.order.PaymentOrderStateService;
import com.inventory.plan.utils.CheckoutRequestHash;
import com.inventory.plan.utils.constants.PlanPaymentConstants;
import com.inventory.plan.validation.PlanValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanPaymentServiceCheckoutTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Mock private PlanRepository planRepository;
  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private PlanMapper planMapper;
  @Mock private PlanValidator planValidator;
  @Mock private PaymentGatewayResolver gatewayResolver;
  @Mock private PaymentGatewayPort gateway;
  @Mock private MetricsWrapper metrics;
  @Mock private OrderPricingService pricing;
  @Mock private PaymentOrderStateService stateService;
  @Mock private OrderReservations reservations;
  @Mock private OrderFulfilmentService fulfilmentService;

  @InjectMocks
  private PlanPaymentService service;

  private final Plan plan = plan();

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    when(planRepository.findByCode("GROWTH")).thenReturn(Optional.of(plan));
    when(pricing.price(eq(plan), any(), eq("shop-1"))).thenReturn(cart());
    when(gatewayResolver.resolve()).thenReturn(gateway);
    when(gatewayResolver.activePublicKey()).thenReturn("rzp_key");
    when(gateway.providerId()).thenReturn(PlanPaymentConstants.PROVIDER_RAZORPAY);
    when(gateway.createCheckout(any())).thenReturn(CreateCheckoutResult.builder().providerOrderId("rzp_1").build());
    when(orderRepository.insert(any(PlanPaymentOrder.class))).thenAnswer(inv -> {
      PlanPaymentOrder order = inv.getArgument(0);
      order.setId("order-1");
      return order;
    });
    when(stateService.attachProviderOrder(eq("order-1"), eq("rzp_1"), any())).thenAnswer(inv -> {
      PlanPaymentOrder order = pending();
      return Optional.of(order);
    });
  }

  @Test
  void createsReservesAndOpensTheOrder() {
    PlanCheckoutResponse response = service.createCheckout("shop-1", request(), "key-1");

    ArgumentCaptor<PlanPaymentOrder> inserted = ArgumentCaptor.forClass(PlanPaymentOrder.class);
    verify(orderRepository).insert(inserted.capture());
    PlanPaymentOrder order = inserted.getValue();
    assertThat(order.getStatus()).isEqualTo(PlanPaymentConstants.STATUS_CREATED);
    assertThat(order.getIdempotencyKey()).isEqualTo("key-1");
    assertThat(order.getRequestHash()).isEqualTo(hash());
    assertThat(order.getExpiresAt()).isEqualTo(NOW.plusSeconds(30 * 60));
    assertThat(order.getAmount()).isEqualByComparingTo("9999");
    verify(reservations).reserve(eq(order), any());
    assertThat(response.getStatus()).isEqualTo(PlanPaymentConstants.STATUS_PAYMENT_PENDING);
    assertThat(response.getRazorpay().getOrderId()).isEqualTo("rzp_1");
    assertThat(response.getRazorpay().getKeyId()).isEqualTo("rzp_key");
  }

  @Test
  void repeatedKeyWithTheSameCartReturnsTheSameOrder() {
    when(orderRepository.findByShopIdAndIdempotencyKey("shop-1", "key-1")).thenReturn(Optional.of(pending()));

    PlanCheckoutResponse response = service.createCheckout("shop-1", request(), "key-1");

    assertThat(response.getOrderId()).isEqualTo("order-1");
    verify(orderRepository, never()).insert(any(PlanPaymentOrder.class));
    verify(gateway, never()).createCheckout(any());
  }

  @Test
  void repeatedKeyWithADifferentCartIsRejected() {
    PlanPaymentOrder other = pending();
    other.setRequestHash("different");
    when(orderRepository.findByShopIdAndIdempotencyKey("shop-1", "key-1")).thenReturn(Optional.of(other));

    assertThatThrownBy(() -> service.createCheckout("shop-1", request(), "key-1"))
        .isInstanceOfSatisfying(BaseException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED));
  }

  @Test
  void repeatedKeyOfAnEndedCheckoutAsksForANewOne() {
    PlanPaymentOrder expired = pending();
    expired.setStatus(PlanPaymentConstants.STATUS_EXPIRED);
    when(orderRepository.findByShopIdAndIdempotencyKey("shop-1", "key-1")).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.createCheckout("shop-1", request(), "key-1"))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void parallelInsertWithTheSameKeyReplaysTheWinner() {
    when(orderRepository.findByShopIdAndIdempotencyKey("shop-1", "key-1"))
        .thenReturn(Optional.empty(), Optional.of(pending()));
    when(orderRepository.insert(any(PlanPaymentOrder.class))).thenThrow(new DuplicateKeyException("dup"));

    assertThat(service.createCheckout("shop-1", request(), "key-1").getOrderId()).isEqualTo("order-1");
    verify(reservations, never()).reserve(any(), any());
  }

  @Test
  void failedReservationCancelsTheOrder() {
    org.mockito.Mockito.doThrow(new ValidationException("Voucher sold out")).when(reservations).reserve(any(), any());

    assertThatThrownBy(() -> service.createCheckout("shop-1", request(), null)).isInstanceOf(ValidationException.class);

    verify(stateService).endUnpaid(eq("order-1"), eq(PlanPaymentConstants.STATUS_CANCELLED), anyString(), any());
    verify(gateway, never()).createCheckout(any());
  }

  @Test
  void gatewayFailureEndsTheOrderAndReleasesReservations() {
    when(gateway.createCheckout(any())).thenThrow(new IllegalStateException("razorpay down"));
    when(stateService.endUnpaid(eq("order-1"), eq(PlanPaymentConstants.STATUS_PAYMENT_FAILED), anyString(), any()))
        .thenReturn(true);

    assertThatThrownBy(() -> service.createCheckout("shop-1", request(), null)).isInstanceOf(IllegalStateException.class);

    verify(reservations).release(any());
  }

  private String hash() {
    CreatePlanCheckoutRequest request = request();
    request.setDurationMonths(12);
    return CheckoutRequestHash.of("shop-1", "GROWTH", request);
  }

  private static CreatePlanCheckoutRequest request() {
    CreatePlanCheckoutRequest request = new CreatePlanCheckoutRequest();
    request.setPlanCode("GROWTH");
    return request;
  }

  private PlanPaymentOrder pending() {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setStatus(PlanPaymentConstants.STATUS_PAYMENT_PENDING);
    order.setProvider(PlanPaymentConstants.PROVIDER_RAZORPAY);
    order.setProviderOrderId("rzp_1");
    order.setRequestHash(hash());
    return order;
  }

  private PricedCart cart() {
    OrderLine line = OrderLine.builder().type("PLAN").code("GROWTH").quantity(1)
        .unitPrice(new BigDecimal("9999")).discount(BigDecimal.ZERO).lineTotal(new BigDecimal("9999")).build();
    return new PricedCart(plan, List.of(line), new BigDecimal("9999"), BigDecimal.ZERO, BigDecimal.ZERO,
        new BigDecimal("9999"), 12);
  }

  private static Plan plan() {
    Plan plan = new Plan();
    plan.setId("plan-1");
    plan.setCode("GROWTH");
    plan.setPlanName("Growth");
    plan.setActive(true);
    return plan;
  }
}
