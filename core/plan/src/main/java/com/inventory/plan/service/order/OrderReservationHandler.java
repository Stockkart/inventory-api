package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PricedCart;

/**
 * Something an order holds between checkout and fulfilment, such as a voucher slot or wallet credit.
 * Every method must be idempotent per order: a retry repeats nothing.
 */
public interface OrderReservationHandler {

  /** Taken when the order is created. Throwing rejects the checkout before any payment. */
  void reserve(PlanPaymentOrder order, PricedCart cart);

  /** Given back when the order fails, expires or is cancelled. */
  void release(PlanPaymentOrder order);

  /**
   * Taken again for a payment that arrived after the order had ended. False when it is gone; the
   * order is then never fulfilled at the reserved price.
   */
  boolean reacquire(PlanPaymentOrder order);
}
