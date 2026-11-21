package com.inventory.hr.utils;

import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Leave years (R2.8, R6.3). A January year is named {@code 2025}; an April year runs 1 April to 31
 * March and is named {@code 2025-26}. Balances group requests by this name.
 */
public final class LeaveYearUtils {

  private static final Pattern CALENDAR = Pattern.compile("(\\d{4})");
  private static final Pattern FISCAL = Pattern.compile("(\\d{4})-(\\d{2})");

  private LeaveYearUtils() {}

  /** Name of the leave year containing {@code date}. */
  public static String leaveYearOf(LocalDate date, LeaveYearStartMonth start) {
    if (start != LeaveYearStartMonth.APRIL) {
      return String.valueOf(date.getYear());
    }
    int first = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
    return fiscalName(first);
  }

  /** First day of the named year; null when the name does not fit the start month. */
  public static LocalDate start(String leaveYear, LeaveYearStartMonth start) {
    if (leaveYear == null) {
      return null;
    }
    if (start != LeaveYearStartMonth.APRIL) {
      Matcher m = CALENDAR.matcher(leaveYear);
      return m.matches() ? LocalDate.of(Integer.parseInt(m.group(1)), 1, 1) : null;
    }
    Matcher m = FISCAL.matcher(leaveYear);
    if (!m.matches()) {
      return null;
    }
    int first = Integer.parseInt(m.group(1));
    // "2025-26" only: the second part must be the next year
    return fiscalName(first).equals(leaveYear) ? LocalDate.of(first, 4, 1) : null;
  }

  /** Last day of the year that starts on {@code start}. */
  public static LocalDate end(LocalDate start) {
    return start.plusYears(1).minusDays(1);
  }

  private static String fiscalName(int firstYear) {
    return firstYear + "-" + String.format("%02d", (firstYear + 1) % 100);
  }
}
