package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.ShopAddOnService;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Grants the order's add-ons. Runs after {@link PlanGrantStep} so annual add-ons can end with the
 * term that order bought (§3.3, §27.5). An add-on-only order bought no term: its annual add-ons end
 * with the term it was priced against at checkout ({@link PlanPaymentOrder#getTermEndsAt()}), never a
 * later renewal; OCR credits never expire, so an OCR-only order always grants.
 */
@Component
@Order(10)
@Slf4j
public class AddOnGrantStep implements OrderFulfilmentStep {

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Autowired
  private PlanTransactionRepository planTransactionRepository;

  Clock clock = Clock.systemUTC();

  @Override
  public void apply(PlanPaymentOrder order) {
    shopAddOnService.grantForOrder(order, termEndsAt(order));
  }

  private Instant termEndsAt(PlanPaymentOrder order) {
    if (!order.isAddOnOnly()) {
      return planTransactionRepository.findFirstByPaymentOrderId(order.getId())
          .map(PlanTransaction::getTermEndsAt)
          .orElseThrow(() -> new IllegalStateException("Order " + order.getId() + " has no plan grant yet"));
    }
    Instant termEndsAt = order.getTermEndsAt();
    if (shopAddOnService.needsTermEnd(order)
        && (termEndsAt == null || !termEndsAt.isAfter(clock.instant()))) {
      // Granting now would hand over add-ons that are already expired: nothing is granted (OCR lines
      // on the same order included, since the whole order is refunded) and an operator refunds it.
      String reason = "Add-on-only order " + order.getId() + " was paid after the term it was priced "
          + "against ended (" + termEndsAt + "); annual add-ons would expire at once. Refund it.";
      log.warn("Shop {}: {}", order.getShopId(), reason);
      throw new OrderNotFulfillableException(reason);
    }
    return termEndsAt;
  }
}
