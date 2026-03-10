package com.inventory.plan.utils;

import java.time.YearMonth;

/**
 * Utility methods for plan and usage logic.
 */
public final class PlanUtils {

  private PlanUtils() {}

  /** Current month key in yyyy-MM format for usage tracking. */
  public static String getCurrentMonthKey() {
    return YearMonth.now().toString();
  }
}
