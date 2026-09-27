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
}
