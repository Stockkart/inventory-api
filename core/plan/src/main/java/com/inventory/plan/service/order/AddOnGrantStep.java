package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PaidTerm;
import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.EntitlementService;
import com.inventory.plan.service.ShopAddOnService;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Grants the order's add-ons. Runs after {@link PlanGrantStep} so annual add-ons can end with the
 * term that order bought (§3.3, §27.5). An add-on-only order bought no term: its annual add-ons end
 * with the shop's current paid term, read now.
 */
@Component
@Order(10)
public class AddOnGrantStep implements OrderFulfilmentStep {

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Autowired
  private PlanTransactionRepository planTransactionRepository;

  @Autowired
  private EntitlementService entitlementService;

  @Override
  public void apply(PlanPaymentOrder order) {
    shopAddOnService.grantForOrder(order, termEndsAt(order));
  }

  /**
   * Throws when an add-on-only order's shop is no longer on a live paid plan, so fulfilment retries
   * and then parks the order in FULFILMENT_FAILED for an operator instead of granting add-ons that
   * would have nothing to end with.
   */
  private Instant termEndsAt(PlanPaymentOrder order) {
    if (order.isAddOnOnly()) {
      return entitlementService.paidTerm(order.getShopId())
          .map(PaidTerm::expiresAt)
          .orElseThrow(() -> new IllegalStateException("Add-on-only order " + order.getId()
              + ": shop " + order.getShopId() + " is no longer on an active paid plan"));
    }
    return planTransactionRepository.findFirstByPaymentOrderId(order.getId())
        .map(PlanTransaction::getTermEndsAt)
        .orElseThrow(() -> new IllegalStateException("Order " + order.getId() + " has no plan grant yet"));
  }
}
