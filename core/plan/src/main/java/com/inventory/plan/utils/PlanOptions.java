package com.inventory.plan.utils;

import com.inventory.common.constants.ErrorCode;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanAction;
import com.inventory.plan.rest.dto.response.PlanOptionResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The action each plan card offers a shop: the one rule behind {@code planOptions} on the plan
 * status. A plan change while the paid plan has days left is refused the same way quote and
 * checkout refuse it (A2), so those cards say so instead of failing at payment.
 */
public final class PlanOptions {

  private PlanOptions() {}

  /**
   * One option per catalogue plan, in catalogue order.
   *
   * @param catalogue the active catalogue, in display order
   * @param paidPlan the shop's paid plan (also when it has ended), or null on a trial or without one
   * @param paidPlanExpiry when the paid plan ends
   * @param now the current time
   */
  public static List<PlanOptionResponse> of(List<Plan> catalogue, Plan paidPlan, Instant paidPlanExpiry,
      Instant now) {
    boolean paidDaysLeft = paidPlan != null && paidPlanExpiry != null && paidPlanExpiry.isAfter(now);
    int paidRank = paidPlan == null ? -1 : rank(catalogue, paidPlan);
    return catalogue.stream()
        .map(plan -> {
          PlanAction action = action(catalogue, plan, paidPlan, paidRank);
          boolean available = action == PlanAction.CURRENT || !paidDaysLeft;
          return PlanOptionResponse.builder()
              .planId(plan.getId())
              .planCode(plan.getCode())
              .action(action)
              .actionAvailable(available)
              .unavailableReason(available ? null : ErrorCode.PLAN_CHANGE_NOT_ALLOWED.getMessage())
              .availableFrom(available ? null : paidPlanExpiry)
              .build();
        })
        .toList();
  }

  /** The same catalogue plan: by id, or by code for a legacy duplicate of it. */
  public static boolean isSamePlan(Plan a, Plan b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.getId() != null && a.getId().equals(b.getId())) {
      return true;
    }
    return a.getCode() != null && a.getCode().equals(b.getCode());
  }

  private static PlanAction action(List<Plan> catalogue, Plan plan, Plan paidPlan, int paidRank) {
    if (paidPlan == null) {
      return PlanAction.BUY;
    }
    if (isSamePlan(plan, paidPlan)) {
      return PlanAction.CURRENT;
    }
    boolean higher = paidRank >= 0
        ? catalogue.indexOf(plan) > paidRank
        : yearlyPrice(plan).compareTo(yearlyPrice(paidPlan)) > 0;
    return higher ? PlanAction.UPGRADE : PlanAction.BUY;
  }

  /** Position of the paid plan in the catalogue, or -1 for a plan no longer listed (legacy). */
  private static int rank(List<Plan> catalogue, Plan paidPlan) {
    for (int i = 0; i < catalogue.size(); i++) {
      if (isSamePlan(catalogue.get(i), paidPlan)) {
        return i;
      }
    }
    return -1;
  }

  /** What a plan is sold for per year: the price a legacy plan no longer in the catalogue is ranked by. */
  private static BigDecimal yearlyPrice(Plan plan) {
    return Objects.requireNonNullElse(plan.getArcPrice(),
        Objects.requireNonNullElse(plan.getPrice(), BigDecimal.ZERO));
  }
}
