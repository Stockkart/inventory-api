package com.inventory.plan.utils;

import java.time.Duration;
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

  /**
   * Whole days from now until {@code expiry}, floored at 0.
   *
   * <p>Truncates rather than rounds, so a trial with 18 hours left reads "0 days remaining" instead
   * of overstating it as 1. Returns null when there is no expiry to count down to.
   */
  public static Integer wholeDaysUntil(Instant expiry) {
    if (expiry == null) {
      return null;
    }
    long days = Duration.between(Instant.now(), expiry).toDays();
    return (int) Math.max(0, days);
  }
}
