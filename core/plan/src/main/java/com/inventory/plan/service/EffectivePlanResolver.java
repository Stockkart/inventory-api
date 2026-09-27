package com.inventory.plan.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.utils.constants.PlanCatalogueConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves which plan a shop is measured against, and which plans the catalogue shows.
 */
@Component
public class EffectivePlanResolver {

  /** Legacy rows have no displayOrder; they keep the old ascending-price order, nulls first as Mongo sorts. */
  private static final Comparator<Plan> CATALOGUE_ORDER =
      Comparator.comparing(Plan::getDisplayOrder, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Plan::getPrice, Comparator.nullsFirst(Comparator.naturalOrder()));

  @Autowired
  private PlanRepository planRepository;

  /**
   * The plan for a shop's {@code planId}; the trial plan when the shop has none.
   */
  public Plan resolve(String planId) {
    if (StringUtils.hasText(planId)) {
      return planRepository.findById(planId)
          .orElseThrow(() -> new ResourceNotFoundException("Plan", "id", planId));
    }
    return trialPlan();
  }

  /**
   * STARTER once the catalogue is seeded, otherwise the legacy Base row.
   */
  public Plan trialPlan() {
    return findTrialPlan()
        .orElseThrow(() -> new ResourceNotFoundException(
            "Plan", "code", PlanCatalogueConstants.TRIAL_PLAN_CODE));
  }

  public Optional<Plan> findTrialPlan() {
    return planRepository.findByCode(PlanCatalogueConstants.TRIAL_PLAN_CODE)
        .or(() -> planRepository.findByPlanName(PlanCatalogueConstants.LEGACY_TRIAL_PLAN_NAME));
  }

  /**
   * Plans shown on the pricing page: not deactivated, by display order then price.
   */
  public List<Plan> activeCatalogue() {
    return planRepository.findAll().stream()
        .filter(EffectivePlanResolver::isActive)
        .sorted(CATALOGUE_ORDER)
        .toList();
  }

  public static boolean isActive(Plan plan) {
    return !Boolean.FALSE.equals(plan.getActive());
  }
}
