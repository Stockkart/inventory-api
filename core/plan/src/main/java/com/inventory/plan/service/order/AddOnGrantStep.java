package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.ShopAddOnService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Grants the order's add-ons. Runs after {@link PlanGrantStep} so annual add-ons can end with the
 * term that order bought (§3.3, §27.5).
 */
@Component
@Order(10)
public class AddOnGrantStep implements OrderFulfilmentStep {

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Autowired
  private PlanTransactionRepository planTransactionRepository;

  @Override
  public void apply(PlanPaymentOrder order) {
    PlanTransaction tx = planTransactionRepository.findFirstByPaymentOrderId(order.getId())
        .orElseThrow(() -> new IllegalStateException("Order " + order.getId() + " has no plan grant yet"));
    shopAddOnService.grantForOrder(order, tx.getTermEndsAt());
  }
}
