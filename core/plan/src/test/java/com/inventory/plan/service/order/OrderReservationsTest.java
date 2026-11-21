package com.inventory.plan.service.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderReservationsTest {

  private final OrderReservationHandler vouchers = mock(OrderReservationHandler.class);
  private final OrderReservationHandler wallet = mock(OrderReservationHandler.class);
  private final OrderReservations reservations = new OrderReservations();
  private final PlanPaymentOrder order = new PlanPaymentOrder();
  private final PricedCart cart = new PricedCart(null, List.of(), null, null, null, null, 12);

  private final PaymentOrderStateService stateService = mock(PaymentOrderStateService.class);

  OrderReservationsTest() {
    ReflectionTestUtils.setField(reservations, "handlers", List.of(vouchers, wallet));
    ReflectionTestUtils.setField(reservations, "stateService", stateService);
    order.setId("order-1");
  }

  @Test
  void anEndedOrderIsReleasedOnlyWhenItsReleaseIsClaimed() {
    java.time.Instant now = java.time.Instant.parse("2026-11-21T10:00:00Z");
    when(stateService.markReservationsReleased("order-1", now)).thenReturn(true, false);

    assertThat(reservations.releaseEnded(order, now)).isTrue();
    assertThat(reservations.releaseEnded(order, now)).isFalse();

    verify(vouchers, org.mockito.Mockito.times(1)).release(order);
    verify(wallet, org.mockito.Mockito.times(1)).release(order);
  }

  @Test
  void rollsBackEarlierReservationsWhenALaterOneFails() {
    doThrow(new IllegalStateException("insufficient balance")).when(wallet).reserve(order, cart);

    assertThatThrownBy(() -> reservations.reserve(order, cart)).isInstanceOf(IllegalStateException.class);

    verify(vouchers).release(order);
    verify(wallet, never()).release(order);
  }

  @Test
  void releaseContinuesPastAFailingHandler() {
    doThrow(new IllegalStateException("boom")).when(vouchers).release(order);

    reservations.release(order);

    verify(wallet).release(order);
  }

  @Test
  void reacquireNeedsEveryHandler() {
    when(vouchers.reacquire(order)).thenReturn(true);
    when(wallet.reacquire(order)).thenReturn(false);

    assertThat(reservations.reacquire(order)).isFalse();
    verify(vouchers).release(order);
  }
}
