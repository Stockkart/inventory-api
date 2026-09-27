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

  OrderReservationsTest() {
    ReflectionTestUtils.setField(reservations, "handlers", List.of(vouchers, wallet));
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
