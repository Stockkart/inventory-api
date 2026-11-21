package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Grants the purchased plan. First, so later steps (add-ons) can read the new term. An add-on-only
 * order buys no plan, so it grants nothing here.
 */
@Component
@Order(0)
public class PlanGrantStep implements OrderFulfilmentStep {

  @Autowired
  private PlanService planService;

  @Override
  public void apply(PlanPaymentOrder order) {
    if (order.isAddOnOnly()) {
      return;
    }
    planService.grantForOrder(order);
  }
}
