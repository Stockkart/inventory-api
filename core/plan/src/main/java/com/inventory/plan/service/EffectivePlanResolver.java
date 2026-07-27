package com.inventory.plan.service;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanKind;
import com.inventory.plan.domain.repository.PlanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the plan whose quotas apply to a shop, including shops on trial.
 *
 * <p>This replaces a lookup for a plan literally named "Base". That name was a single point of
 * failure: the row had been deleted from the catalog, so every shop without a {@code planId} — i.e.
 * every trial shop — got a 500 from the plan status endpoint, which is the endpoint the trial and
 * upgrade flow depends on.
 *
 * <p>The fallback chain is deliberately total. A shop with no plan resolves to the entry tier, and a
 * catalog with no tiers at all resolves to a permissive synthetic plan rather than throwing —
 * reporting generous limits is a far better failure than refusing to report status.
 */
@Component
@Slf4j
public class EffectivePlanResolver {

  @Autowired
  private PlanRepository planRepository;

  /** Marker so callers can tell a real catalog row from the emergency fallback. */
  public static final String UNRESOLVED_PLAN_NAME = "Unresolved";

  /**
   * Plan backing a shop's quota checks.
   *
   * @param planId the shop's stored plan id; null or blank for a trial shop
   */
  public Plan forShop(String planId) {
    if (planId != null && !planId.isBlank()) {
      Optional<Plan> stored = planRepository.findById(planId);
      if (stored.isPresent()) {
        return stored.get();
      }
      // A deleted plan should not lock the shop out of its own status page.
      log.warn("Shop references missing plan {} — falling back to entry tier", planId);
    }
    return entryPlan();
  }

  /** Cheapest subscription tier, or a permissive placeholder when the catalog has none. */
  public Plan entryPlan() {
    List<Plan> all = planRepository.findAll();

    Optional<Plan> lowestTier = all.stream()
        .filter(p -> p.getKind() == null || p.getKind() == PlanKind.PLAN)
        .min(Comparator.comparing(EffectivePlanResolver::rankOf));

    if (lowestTier.isPresent()) {
      return lowestTier.get();
    }

    log.warn("Plan catalog has no subscription tiers — using permissive placeholder");
    return placeholder();
  }

  /**
   * Ladder position, falling back to price then to last place.
   *
   * <p>Rows without a rank must sort <em>after</em> ranked ones — Mongo would order null first,
   * which would let an unranked legacy row masquerade as the entry tier.
   */
  private static BigDecimal rankOf(Plan plan) {
    if (plan.getTierRank() != null) {
      return BigDecimal.valueOf(plan.getTierRank());
    }
    if (plan.getArcPrice() != null) {
      return plan.getArcPrice();
    }
    return BigDecimal.valueOf(Long.MAX_VALUE);
  }

  /** Unlimited stand-in: never the reason a shop is told it hit a cap. */
  private static Plan placeholder() {
    Plan plan = new Plan();
    plan.setPlanName(UNRESOLVED_PLAN_NAME);
    plan.setKind(PlanKind.PLAN);
    plan.setUnlimited(true);
    plan.setPrice(BigDecimal.ZERO);
    plan.setArcPrice(BigDecimal.ZERO);
    return plan;
  }
}
