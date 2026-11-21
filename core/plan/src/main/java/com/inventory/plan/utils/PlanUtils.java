package com.inventory.plan.utils;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.utils.constants.PricingConstants;
import java.math.BigDecimal;
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

  /** Anchor price for catalogue plans; null for legacy rows, which never show one. */
  public static BigDecimal listPrice(Plan plan) {
    if (plan == null || plan.getCode() == null || plan.getArcPrice() == null) {
      return null;
    }
    return plan.getArcPrice().add(PricingConstants.LIST_PRICE_MARKUP);
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

  /**
   * Rupees as messages show them: Indian digit grouping ("₹1,00,000"), paise only when not whole
   * ("₹999.90").
   */
  public static String rupees(BigDecimal amount) {
    BigDecimal value = amount == null ? BigDecimal.ZERO : amount;
    boolean whole = value.stripTrailingZeros().scale() <= 0;
    String plain = value.abs().setScale(whole ? 0 : 2, java.math.RoundingMode.HALF_UP).toPlainString();
    int dot = plain.indexOf('.');
    String integer = dot < 0 ? plain : plain.substring(0, dot);
    String fraction = dot < 0 ? "" : plain.substring(dot);
    StringBuilder grouped = new StringBuilder();
    int len = integer.length();
    if (len <= 3) {
      grouped.append(integer);
    } else {
      String head = integer.substring(0, len - 3);
      for (int i = 0; i < head.length(); i++) {
        if (i > 0 && (head.length() - i) % 2 == 0) {
          grouped.append(',');
        }
        grouped.append(head.charAt(i));
      }
      grouped.append(',').append(integer, len - 3, len);
    }
    return (value.signum() < 0 ? "-\u20b9" : "\u20b9") + grouped + fraction;
  }
}
