package com.inventory.product.utils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Indian financial year helpers (1 Apr – 31 Mar), timezone Asia/Kolkata.
 */
public final class FinancialYear {

  public static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  private FinancialYear() {}

  public static String currentLabel() {
    return labelFor(LocalDate.now(IST));
  }

  public static String currentLabel(Clock clock) {
    return labelFor(LocalDate.now(clock.withZone(IST)));
  }

  /**
   * e.g. 3 Aug 2026 → {@code 2026-27}; 15 Mar 2027 → {@code 2026-27}; 1 Apr 2027 → {@code 2027-28}.
   */
  public static String labelFor(LocalDate date) {
    int startYear = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    int endYearShort = (startYear + 1) % 100;
    return startYear + "-" + String.format("%02d", endYearShort);
  }
}
