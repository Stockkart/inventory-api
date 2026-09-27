package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link OrderReservationHandler} together.
 */
@Component
@Slf4j
public class OrderReservations {

  @Autowired(required = false)
  private List<OrderReservationHandler> handlers = new ArrayList<>();

  /** On failure, what was already reserved is released before rethrowing. */
  public void reserve(PlanPaymentOrder order, PricedCart cart) {
    List<OrderReservationHandler> taken = new ArrayList<>();
    try {
      for (OrderReservationHandler handler : handlers) {
        handler.reserve(order, cart);
        taken.add(handler);
      }
    } catch (RuntimeException e) {
      taken.forEach(handler -> releaseQuietly(handler, order));
      throw e;
    }
  }

  public void release(PlanPaymentOrder order) {
    handlers.forEach(handler -> releaseQuietly(handler, order));
  }

  /** All or nothing: if any handler cannot reacquire, the ones that did are released again. */
  public boolean reacquire(PlanPaymentOrder order) {
    List<OrderReservationHandler> taken = new ArrayList<>();
    for (OrderReservationHandler handler : handlers) {
      if (!handler.reacquire(order)) {
        taken.forEach(done -> releaseQuietly(done, order));
        return false;
      }
      taken.add(handler);
    }
    return true;
  }

  private static void releaseQuietly(OrderReservationHandler handler, PlanPaymentOrder order) {
    try {
      handler.release(order);
    } catch (RuntimeException e) {
      log.error("Releasing {} for order {} failed and needs an operator: {}",
          handler.getClass().getSimpleName(), order.getId(), e.getMessage(), e);
    }
  }
}
