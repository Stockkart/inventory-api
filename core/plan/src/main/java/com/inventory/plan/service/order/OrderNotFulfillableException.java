package com.inventory.plan.service.order;

/**
 * Thrown by a fulfilment step when a paid order can never be fulfilled as bought (retrying would not
 * help). The order goes straight to FULFILMENT_FAILED for an operator refund, like a late payment
 * whose reservations are gone (§27.2).
 */
public class OrderNotFulfillableException extends RuntimeException {

  public OrderNotFulfillableException(String message) {
    super(message);
  }
}
