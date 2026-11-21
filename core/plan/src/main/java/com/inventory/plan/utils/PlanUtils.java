package com.inventory.plan.utils;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Utility methods for plan and usage logic.
 */
public final class PlanUtils {

  private PlanUtils() {}

  /** Current month key in yyyy-MM format for usage tracking. */
  public static String getCurrentMonthKey() {
    return YearMonth.now().toString();
  }

  /** Instant does not support calendar months; convert via UTC local date. */
  public static Instant plusMonths(Instant instant, int months) {
    return instant.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
  }

  public static boolean isExpired(Instant planExpiryDate) {
    return planExpiryDate != null && planExpiryDate.isBefore(Instant.now());
  }

  /** Legacy add-ons stored as plan rows; they are sold at payment, never picked as a plan. */
  static final java.util.Set<String> ADD_ON_PLAN_NAMES = java.util.Set.of("extra user plan", "extra shop plan");

  /** False for legacy add-on rows ("Extra User Plan", "Extra Shop Plan"): not a plan a shop picks. */
  public static boolean isSelectable(com.inventory.plan.domain.model.Plan plan) {
    String name = plan.getPlanName() == null ? "" : plan.getPlanName().trim().toLowerCase(java.util.Locale.ROOT);
    return !ADD_ON_PLAN_NAMES.contains(name);
  }

  /** Monthly billing limit as the apps show it: "Unlimited", or rupees in lakh such as "₹1.5L". */
  public static String billingLimitLabel(com.inventory.plan.domain.model.Plan plan) {
    if (plan.isUnlimited() || plan.getBillingLimit() == null) {
      return "Unlimited";
    }
    java.math.BigDecimal lakh = plan.getBillingLimit().divide(java.math.BigDecimal.valueOf(100000), 1,
        java.math.RoundingMode.HALF_UP);
    return "\u20b9" + lakh.toPlainString() + "L";
  }
}
