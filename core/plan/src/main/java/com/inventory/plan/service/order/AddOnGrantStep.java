package com.inventory.plan.service.order;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.service.EntitlementService;
import com.inventory.plan.service.ShopAddOnService;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
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

  @Autowired
  private EntitlementService entitlementService;

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
    Instant now = clock.instant();
    Optional<String> twice = shopAddOnService.alreadyHeldOnce(order, now);
    if (twice.isPresent()) {
      String reason = "Add-on-only order " + order.getId() + " buys " + twice.get()
          + ", which the shop already has from another order. Refund it.";
      log.warn("Shop {}: {}", order.getShopId(), reason);
      throw new OrderNotFulfillableException(reason);
    }
    Instant termEndsAt = order.getTermEndsAt();
    boolean needsTerm = shopAddOnService.needsTermEnd(order);
    if (needsTerm && entitlementService.paidTerm(order.getShopId()).isEmpty()) {
      String reason = "Add-on-only order " + order.getId() + " was paid after the shop's paid plan ended "
          + "or was refunded; annual add-ons need one. Refund it.";
      log.warn("Shop {}: {}", order.getShopId(), reason);
      throw new OrderNotFulfillableException(reason);
    }
    if (needsTerm
        && (termEndsAt == null || !termEndsAt.isAfter(now))) {
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
