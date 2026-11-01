package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;

/**
 * Undoes one part of fulfilment for a refunded order. Must be idempotent: a failed refund is retried
 * from the first step, and a step may meet an order whose fulfilment only partly ran.
 */
public interface OrderRefundStep {

  void reverse(PlanPaymentOrder order);
}
