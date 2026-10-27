package com.inventory.hr.service;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Works out what one employee-day shows (design D2/D4, R5.4, R5.7–R5.9, R5.13, R6.10). Pure: no
 * repository, clock or Spring bean, so it is property-tested ({@code DayStatusResolverProperties}).
 *
 * <p>Precedence, first match wins:
 *
 * <ol>
 *   <li>outside employment (before {@code joinDate} or after {@code exitDate}) → NOT_EMPLOYED;
 *   <li>approved full-day leave → LEAVE, whatever is stored (D4: the overlay beats a mark, so a
 *       mark left behind by a lost clean-up never shows);
 *   <li>stored mark → that status (PRESENT may become HALF_DAY by the half-day rule);
 *   <li>shop holiday → HOLIDAY;
 *   <li>weekly off for the date's salary month ({@code effectiveFrom} schedule) → WEEK_OFF;
 *   <li>otherwise UNMARKED. ABSENT is never derived (R5.13): it only comes from a stored mark.
 * </ol>
 *
 * Leave covers working days only (R6.10 "skipping week-offs and holidays", the same days {@link
 * LeaveDayCounter} counts), so a leave range over a Sunday shows that Sunday as WEEK_OFF, or as
 * what was stored for it (work on an off day). A half-day leave gives 0.5 leave plus half of the
 * stored mark: PRESENT or HALF_DAY → the other half PRESENT, ABSENT → ABSENT, nothing → UNMARKED.
 */
public final class DayStatusResolver {

  private DayStatusResolver() {}

  /** What decided the status. */
  public enum Source {
    EMPLOYMENT,
    LEAVE,
    MARK,
    HOLIDAY,
    WEEK_OFF,
    NONE
  }

  /**
   * One resolved day.
   *
   * @param otherHalf for a half-day leave, what the other half is (PRESENT, ABSENT, UNMARKED, or
   *     a stored WEEK_OFF/HOLIDAY override); null otherwise
   * @param workedMinutes null when no in and out time
   * @param autoHalfDay a PRESENT mark shown as HALF_DAY because too few hours were worked (R2.5)
   */
  public record Resolution(
      DayStatus status,
      Source source,
      boolean halfDayLeave,
      DayStatus otherHalf,
      String leaveRequestId,
      boolean late,
      Integer workedMinutes,
      int overtimeMinutes,
      boolean autoHalfDay) {

    /** 1 for a full-day leave, 0.5 for a half-day leave, else 0. */
    public BigDecimal leaveDays() {
      if (status != DayStatus.LEAVE) {
        return BigDecimal.ZERO;
      }
      return halfDayLeave ? new BigDecimal("0.5") : BigDecimal.ONE;
    }
  }

  /**
   * @param date the day
   * @param employee needs {@code joinDate}, {@code exitDate}, {@code workStart}, {@code workEnd}
   * @param mark stored day of this employee and date; null = none
   * @param leave approved leave covering the date; null = none
   * @param holiday the date is a shop holiday
   * @param settings effective settings (defaults filled in)
   */
  public static Resolution resolve(LocalDate date, Employee employee, AttendanceDay mark,
      LeaveRequest leave, boolean holiday, HrSettings settings) {
    if (!isEmployed(date, employee)) {
      return new Resolution(DayStatus.NOT_EMPLOYED, Source.EMPLOYMENT, false, null, null, false,
          null, 0, false);
    }
    boolean weekOff = isWeekOff(date, settings);
    // leave never covers a week-off or holiday (R6.10), so those keep their own status below
    boolean leaveApplies = leave != null && !holiday && !weekOff;
    Times times = mark != null ? times(mark, employee, settings) : Times.NONE;

    if (leaveApplies && !Boolean.TRUE.equals(leave.getHalfDay())) {
      // full-day leave wins over any stored mark (D4)
      return new Resolution(DayStatus.LEAVE, Source.LEAVE, false, null, leave.getId(), false,
          null, 0, false);
    }
    if (leaveApplies) {
      DayStatus other = mark == null ? DayStatus.UNMARKED : halfOf(mark.getStatus());
      boolean worked = other == DayStatus.PRESENT;
      return new Resolution(DayStatus.LEAVE, Source.LEAVE, true, other, leave.getId(),
          worked && times.late(), worked ? times.worked() : null,
          worked ? times.overtime() : 0, false);
    }
    if (mark != null && mark.getStatus() != null) {
      DayStatus status = DayStatus.of(mark.getStatus());
      boolean auto = Boolean.TRUE.equals(mark.getAutoHalfDay())
          || isAutoHalfDay(mark.getStatus(), times.worked(), settings);
      if (auto && status == DayStatus.PRESENT) {
        status = DayStatus.HALF_DAY;
      }
      boolean worked = status == DayStatus.PRESENT || status == DayStatus.HALF_DAY;
      return new Resolution(status, Source.MARK, false, null, null, worked && times.late(),
          worked ? times.worked() : null, worked ? times.overtime() : 0,
          auto && status == DayStatus.HALF_DAY);
    }
    if (holiday) {
      return new Resolution(DayStatus.HOLIDAY, Source.HOLIDAY, false, null, null, false, null, 0,
          false);
    }
    if (weekOff) {
      return new Resolution(DayStatus.WEEK_OFF, Source.WEEK_OFF, false, null, null, false, null,
          0, false);
    }
    return new Resolution(DayStatus.UNMARKED, Source.NONE, false, null, null, false, null, 0,
        false);
  }

  /**
   * Between join and exit date, both inclusive. A missing join date falls back to the
   * {@code createdAt} date ({@link HrDateUtils#effectiveJoinDate}).
   */
  public static boolean isEmployed(LocalDate date, Employee employee) {
    LocalDate join = HrDateUtils.effectiveJoinDate(employee);
    LocalDate exit = HrDateUtils.parseDate(employee.getExitDate());
    return (join == null || !date.isBefore(join)) && (exit == null || !date.isAfter(exit));
  }

  /**
   * Weekly off by the schedule entry that applies to the date's salary month ({@link PayCycle}):
   * a weekly-off change "from 2026-10" with start day 26 applies from 26 Sep 2026.
   */
  public static boolean isWeekOff(LocalDate date, HrSettings settings) {
    String period = PayCycle.periodOf(date, PayCycle.startDay(settings)).toString();
    List<DayOfWeek> days = HrSettingsService.weeklyOffFor(settings, period);
    return days.contains(date.getDayOfWeek());
  }

  /**
   * Worked minutes from in and out time; null when either is missing or out is not after in (no
   * overnight shifts in v1).
   */
  public static Integer workedMinutes(String inTime, String outTime) {
    LocalTime in = HrDateUtils.parseTime(inTime);
    LocalTime out = HrDateUtils.parseTime(outTime);
    if (in == null || out == null || !out.isAfter(in)) {
      return null;
    }
    return (int) Duration.between(in, out).toMinutes();
  }

  /** R2.5: a PRESENT day with fewer worked minutes than {@code halfDayBelowHours} is a half day. */
  public static boolean isAutoHalfDay(AttendanceStatus status, Integer workedMinutes,
      HrSettings settings) {
    if (status != AttendanceStatus.PRESENT || workedMinutes == null
        || settings.getHalfDayBelowHours() == null) {
      return false;
    }
    BigDecimal threshold = settings.getHalfDayBelowHours().multiply(BigDecimal.valueOf(60));
    return BigDecimal.valueOf(workedMinutes).compareTo(threshold) < 0;
  }

  private static DayStatus halfOf(AttendanceStatus stored) {
    if (stored == null) {
      return DayStatus.UNMARKED;
    }
    // a half-day mark on a half-day-leave day is the worked half: the leave is the other half
    return stored == AttendanceStatus.HALF_DAY ? DayStatus.PRESENT : DayStatus.of(stored);
  }

  private record Times(boolean late, Integer worked, int overtime) {
    static final Times NONE = new Times(false, null, 0);
  }

  /**
   * Late (R5.7): in-time after start + grace. Overtime (R5.9): only with overtime on, minutes
   * worked beyond the working day. Employee hours override shop hours (R4.4).
   */
  private static Times times(AttendanceDay mark, Employee employee, HrSettings settings) {
    LocalTime start = HrDateUtils.parseTime(employee.getWorkStart() != null
        ? employee.getWorkStart() : settings.getWorkStart());
    LocalTime end = HrDateUtils.parseTime(employee.getWorkEnd() != null
        ? employee.getWorkEnd() : settings.getWorkEnd());
    LocalTime in = HrDateUtils.parseTime(mark.getInTime());
    int grace = settings.getGraceMinutes() != null ? settings.getGraceMinutes() : 0;
    boolean late = in != null && start != null && in.isAfter(start.plusMinutes(grace))
        // plusMinutes wraps past midnight; a start this late has no "late" in v1
        && !start.plusMinutes(grace).isBefore(start);
    Integer worked = workedMinutes(mark.getInTime(), mark.getOutTime());
    int overtime = 0;
    if (Boolean.TRUE.equals(settings.getOvertimeEnabled()) && worked != null && start != null
        && end != null && end.isAfter(start)) {
      int day = (int) Duration.between(start, end).toMinutes();
      overtime = Math.max(0, worked - day);
    }
    return new Times(late, worked, overtime);
  }
}
