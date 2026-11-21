package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Precedence and time rules of the resolver (R5.4, R5.7–R5.9, R5.13, R2.5). */
class DayStatusResolverTest {

  /** Tuesday. */
  private static final LocalDate TUE = LocalDate.of(2025, 4, 8);
  /** Sunday, the default weekly off. */
  private static final LocalDate SUN = LocalDate.of(2025, 4, 6);

  private static HrSettings settings() {
    return HrSettingsService.withDefaults(null, SHOP);
  }

  private static Employee employee() {
    Employee e = new Employee();
    e.setId("e1");
    e.setJoinDate("2025-01-01");
    return e;
  }

  private static AttendanceDay mark(AttendanceStatus status, String in, String out) {
    AttendanceDay d = new AttendanceDay();
    d.setStatus(status);
    d.setInTime(in);
    d.setOutTime(out);
    return d;
  }

  private static LeaveRequest leave(boolean halfDay) {
    LeaveRequest l = new LeaveRequest();
    l.setId("l1");
    l.setHalfDay(halfDay);
    return l;
  }

  private static DayStatusResolver.Resolution resolve(LocalDate date, AttendanceDay mark,
      LeaveRequest leave, boolean holiday) {
    return DayStatusResolver.resolve(date, employee(), mark, leave, holiday, settings());
  }

  @Test
  void nothingStoredOnAWorkingDayIsUnmarkedNeverAbsent() {
    assertEquals(DayStatus.UNMARKED, resolve(TUE, null, null, false).status());
  }

  @Test
  void aMissingJoinDateFallsBackToTheCreatedDateInIndia() {
    Employee e = employee();
    e.setJoinDate(null);
    // 2026-10-25T19:00Z is 26 Oct 00:30 in Asia/Kolkata
    e.setCreatedAt(java.time.Instant.parse("2026-10-25T19:00:00Z"));
    assertEquals(DayStatus.NOT_EMPLOYED, DayStatusResolver.resolve(LocalDate.of(2026, 10, 23),
        e, null, null, false, settings()).status());
    assertEquals(DayStatus.NOT_EMPLOYED, DayStatusResolver.resolve(LocalDate.of(2026, 10, 25),
        e, null, null, false, settings()).status());
    assertEquals(DayStatus.UNMARKED, DayStatusResolver.resolve(LocalDate.of(2026, 10, 26),
        e, null, null, false, settings()).status());
  }

  @Test
  void beforeJoiningOrAfterExitIsNotEmployed() {
    Employee e = employee();
    e.setExitDate("2025-04-07");
    assertEquals(DayStatus.NOT_EMPLOYED,
        DayStatusResolver.resolve(TUE, e, null, null, false, settings()).status());
    assertEquals(DayStatus.NOT_EMPLOYED, resolve(LocalDate.of(2024, 12, 31), null, null, false)
        .status());
  }

  @Test
  void fullDayLeaveBeatsAStoredMark() {
    DayStatusResolver.Resolution r = resolve(TUE, mark(AttendanceStatus.ABSENT, null, null),
        leave(false), false);
    assertEquals(DayStatus.LEAVE, r.status());
    assertEquals("l1", r.leaveRequestId());
    assertEquals(BigDecimal.ONE, r.leaveDays());
  }

  @Test
  void leaveDoesNotCoverWeekOffsOrHolidays() {
    assertEquals(DayStatus.WEEK_OFF, resolve(SUN, null, leave(false), false).status());
    assertEquals(DayStatus.HOLIDAY, resolve(TUE, null, leave(false), true).status());
    // work on a Sunday inside a leave range stays visible
    assertEquals(DayStatus.PRESENT,
        resolve(SUN, mark(AttendanceStatus.PRESENT, null, null), leave(false), false).status());
  }

  @Test
  void aStoredMarkBeatsHolidayAndWeekOff() {
    assertEquals(DayStatus.PRESENT,
        resolve(TUE, mark(AttendanceStatus.PRESENT, null, null), null, true).status());
    assertEquals(DayStatus.ABSENT,
        resolve(SUN, mark(AttendanceStatus.ABSENT, null, null), null, false).status());
    assertEquals(DayStatus.HOLIDAY, resolve(TUE, null, null, true).status());
    assertEquals(DayStatus.WEEK_OFF, resolve(SUN, null, null, false).status());
  }

  @Test
  void halfDayLeaveIsHalfLeavePlusHalfOfTheMark() {
    DayStatusResolver.Resolution none = resolve(TUE, null, leave(true), false);
    assertTrue(none.halfDayLeave());
    assertEquals(DayStatus.UNMARKED, none.otherHalf());
    assertEquals(new BigDecimal("0.5"), none.leaveDays());

    assertEquals(DayStatus.PRESENT,
        resolve(TUE, mark(AttendanceStatus.PRESENT, null, null), leave(true), false).otherHalf());
    assertEquals(DayStatus.PRESENT,
        resolve(TUE, mark(AttendanceStatus.HALF_DAY, null, null), leave(true), false).otherHalf());
    assertEquals(DayStatus.ABSENT,
        resolve(TUE, mark(AttendanceStatus.ABSENT, null, null), leave(true), false).otherHalf());
  }

  @Test
  void lateAfterStartPlusGraceUsingEmployeeHoursFirst() {
    // shop 09:00 + 15 grace
    assertFalse(resolve(TUE, mark(AttendanceStatus.PRESENT, "09:15", "18:00"), null, false)
        .late());
    assertTrue(resolve(TUE, mark(AttendanceStatus.PRESENT, "09:16", "18:00"), null, false)
        .late());
    Employee e = employee();
    e.setWorkStart("10:00");
    assertFalse(DayStatusResolver.resolve(TUE, e,
        mark(AttendanceStatus.PRESENT, "10:10", "20:00"), null, false, settings()).late());
  }

  @Test
  void workedMinutesAndAutoHalfDay() {
    DayStatusResolver.Resolution r =
        resolve(TUE, mark(AttendanceStatus.PRESENT, "09:00", "12:00"), null, false);
    assertEquals(180, r.workedMinutes());
    // below the 4 hour half-day rule
    assertEquals(DayStatus.HALF_DAY, r.status());
    assertTrue(r.autoHalfDay());
    DayStatusResolver.Resolution full =
        resolve(TUE, mark(AttendanceStatus.PRESENT, "09:00", "13:00"), null, false);
    assertEquals(DayStatus.PRESENT, full.status());
    assertNull(resolve(TUE, mark(AttendanceStatus.PRESENT, "18:00", "09:00"), null, false)
        .workedMinutes());
  }

  @Test
  void overtimeOnlyWhenEnabled() {
    HrSettings s = settings();
    AttendanceDay long12h = mark(AttendanceStatus.PRESENT, "08:00", "22:00");
    assertEquals(0, DayStatusResolver.resolve(TUE, employee(), long12h, null, false, s)
        .overtimeMinutes());
    s.setOvertimeEnabled(true);
    // 840 worked − 720 working day (09:00–21:00)
    assertEquals(120, DayStatusResolver.resolve(TUE, employee(), long12h, null, false, s)
        .overtimeMinutes());
  }

  @Test
  void theWeeklyOffScheduleFollowsEffectiveFrom() {
    HrSettings s = settings();
    s.setWeeklyOffSchedule(List.of(
        new HrSettings.WeeklyOffEntry("0000-01", List.of(DayOfWeek.SUNDAY)),
        new HrSettings.WeeklyOffEntry("2025-05", List.of(DayOfWeek.TUESDAY))));
    assertEquals(DayStatus.UNMARKED,
        DayStatusResolver.resolve(TUE, employee(), null, null, false, s).status());
    assertEquals(DayStatus.WEEK_OFF, DayStatusResolver.resolve(LocalDate.of(2025, 5, 6),
        employee(), null, null, false, s).status());
  }
}
