package com.inventory.plan.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.repository.PlanPaymentOrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderFulfilmentServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");

  @Mock private PaymentOrderStateService stateService;
  @Mock private PlanPaymentOrderRepository orderRepository;
  @Mock private OrderReservations reservations;
  @Mock private OrderFulfilmentStep grant;
  @Mock private OrderFulfilmentStep addOns;

  @InjectMocks
  private OrderFulfilmentService service;

  private final PlanPaymentOrder stored = order(1, false);

  @BeforeEach
  void setUp() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "steps", List.of(grant, addOns));
    when(orderRepository.findById("order-1")).thenReturn(Optional.of(stored));
  }

  @Test
  void runsEveryStepThenCompletes() {
    PlanPaymentOrder claimed = order(1, false);
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.of(claimed));
    when(stateService.complete("order-1", NOW, NOW)).thenReturn(true);

    assertThat(service.fulfil("order-1")).isSameAs(stored);

    verify(grant).apply(claimed);
    verify(addOns).apply(claimed);
    verify(stateService).complete("order-1", NOW, NOW);
  }

  @Test
  void doesNothingWhenAnotherAttemptHoldsTheClaim() {
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.empty());

    service.fulfil("order-1");

    verify(grant, never()).apply(any());
    verify(stateService, never()).complete(anyString(), any(), any());
  }

  @Test
  void leavesAFailedAttemptForRetryUntilTheLimit() {
    PlanPaymentOrder claimed = order(1, false);
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.of(claimed));
    doThrow(new IllegalStateException("mongo down")).when(addOns).apply(claimed);

    service.fulfil("order-1");

    verify(stateService).recordAttemptFailure(eq("order-1"), eq(NOW), anyString(), eq(NOW));
    verify(stateService, never()).failFulfilment(anyString(), any(), anyString(), any());
    verify(stateService, never()).complete(anyString(), any(), any());
  }

  @Test
  void parksTheOrderAfterTheLastAttempt() {
    PlanPaymentOrder claimed = order(3, false);
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.of(claimed));
    doThrow(new IllegalStateException("mongo down")).when(grant).apply(claimed);

    service.fulfil("order-1");

    verify(stateService).failFulfilment(eq("order-1"), eq(NOW), anyString(), eq(NOW));
  }

  @Test
  void latePaymentThatCannotReacquireReservationsNeedsAnOperator() {
    PlanPaymentOrder claimed = order(1, true);
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.of(claimed));
    when(reservations.reacquire(claimed)).thenReturn(false);

    service.fulfil("order-1");

    verify(grant, never()).apply(any());
    verify(stateService).failFulfilment(eq("order-1"), eq(NOW), anyString(), eq(NOW));
  }

  @Test
  void latePaymentThatReacquiresIsFulfilled() {
    PlanPaymentOrder claimed = order(1, true);
    when(stateService.claim(eq("order-1"), eq(NOW), any())).thenReturn(Optional.of(claimed));
    when(reservations.reacquire(claimed)).thenReturn(true);
    when(stateService.complete("order-1", NOW, NOW)).thenReturn(true);

    service.fulfil("order-1");

    verify(grant).apply(claimed);
    verify(stateService).complete("order-1", NOW, NOW);
  }

  private static PlanPaymentOrder order(int attempts, boolean late) {
    PlanPaymentOrder order = new PlanPaymentOrder();
    order.setId("order-1");
    order.setShopId("shop-1");
    order.setClaimedAt(NOW);
    order.setFulfilmentAttempts(attempts);
    order.setLatePayment(late);
    return order;
  }
}
