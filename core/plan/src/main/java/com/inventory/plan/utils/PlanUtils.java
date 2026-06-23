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
}
