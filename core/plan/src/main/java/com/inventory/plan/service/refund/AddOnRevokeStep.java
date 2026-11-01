package com.inventory.plan.service.refund;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.service.ShopAddOnService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class AddOnRevokeStep implements OrderRefundStep {

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Override
  public void reverse(PlanPaymentOrder order) {
    shopAddOnService.revokeForOrder(order);
  }
}
