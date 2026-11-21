package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;

/**
 * One effect of a paid order, such as granting the plan or redeeming a voucher. Steps run in
 * {@link org.springframework.core.annotation.Order} order and must be idempotent per order, because
 * a crashed attempt is retried from the first step.
 */
public interface OrderFulfilmentStep {

  void apply(PlanPaymentOrder order);
}
