package com.inventory.hr.service;

import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Leave day counting (R6.5, R6.10): a request counts only working days, i.e. dates that are
 * neither a shop holiday nor a weekly off under the schedule of that date's month. Pure, so
 * {@code LeaveProperties} can check it; the same rule decides which days {@link DayStatusResolver}
 * shows as LEAVE.
 */
public final class LeaveDayCounter {

  static final BigDecimal HALF = new BigDecimal("0.5");

  private LeaveDayCounter() {}

  /** Working dates in {@code [from, to]}, in order. {@code holidays} holds yyyy-MM-dd strings. */
  public static List<LocalDate> workingDates(LocalDate from, LocalDate to,
      Collection<String> holidays, HrSettings settings) {
    List<LocalDate> dates = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (!holidays.contains(HrDateUtils.format(d)) && !DayStatusResolver.isWeekOff(d, settings)) {
        dates.add(d);
      }
    }
    return dates;
  }

  /** Leave days: the working dates, or 0.5 for a half-day request on a working day. */
  public static BigDecimal days(List<LocalDate> workingDates, boolean halfDay) {
    if (workingDates.isEmpty()) {
      return BigDecimal.ZERO;
    }
    return halfDay ? HALF : BigDecimal.valueOf(workingDates.size());
  }
}
