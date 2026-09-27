package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.PlanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(0)
public class PlanRevokeStep implements OrderRefundStep {

  @Autowired
  private PlanService planService;

  @Override
  public void reverse(PlanPaymentOrder order) {
    planService.revokeForOrder(order);
  }
}
