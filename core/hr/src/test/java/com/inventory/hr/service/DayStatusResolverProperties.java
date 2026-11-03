// Feature: hr-module, Property 2: Every employee-day gets exactly one status; leave wins; ABSENT is never derived
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Day status resolution is total and never invents Absent (design Property 2/5, task 5.2): for
 * any employment window, date, stored mark, leave, holiday flag and weekly-off schedule the
 * resolver returns exactly one status; approved full-day leave on a working day always wins;
 * ABSENT shows only when it was stored; nothing applicable gives UNMARKED.
 *
 * <p><b>Validates: Requirements 3.3, 5.4, 5.13, 6.10</b>
 */
class DayStatusResolverProperties {

  private static final LocalDate BASE = LocalDate.of(2025, 1, 1);

  record Case(LocalDate date, Employee employee, AttendanceDay mark, LeaveRequest leave,
      boolean holiday, HrSettings settings) {}

  @Property(tries = 100)
  void exactlyOneStatusAndLeaveWinsAndAbsentIsNeverDerived(@ForAll("cases") Case c) {
    DayStatusResolver.Resolution r = DayStatusResolver.resolve(c.date(), c.employee(), c.mark(),
        c.leave(), c.holiday(), c.settings());

    assertNotNull(r.status(), "every day has a status");
    assertNotNull(r.source());
    boolean employed = DayStatusResolver.isEmployed(c.date(), c.employee());
    boolean weekOff = DayStatusResolver.isWeekOff(c.date(), c.settings());
    boolean working = !weekOff && !c.holiday();
    AttendanceStatus stored = c.mark() != null ? c.mark().getStatus() : null;

    if (!employed) {
      assertEquals(DayStatus.NOT_EMPLOYED, r.status());
      return;
    }
    assertNotEquals(DayStatus.NOT_EMPLOYED, r.status());
    if (c.leave() != null && working) {
      // leave wins over any stored mark (full day) or shares the day with it (half day)
      assertEquals(DayStatus.LEAVE, r.status());
      assertEquals(Boolean.TRUE.equals(c.leave().getHalfDay()), r.halfDayLeave());
      BigDecimal expected = r.halfDayLeave() ? new BigDecimal("0.5") : BigDecimal.ONE;
      assertEquals(expected, r.leaveDays());
    } else {
      assertNotEquals(DayStatus.LEAVE, r.status(), "leave never covers an off day");
    }
    if (r.status() == DayStatus.ABSENT || r.otherHalf() == DayStatus.ABSENT) {
      assertEquals(AttendanceStatus.ABSENT, stored, "ABSENT must come from a stored mark");
    }
    if (stored == null && (c.leave() == null || !working)) {
      DayStatus expected = c.holiday() ? DayStatus.HOLIDAY
          : weekOff ? DayStatus.WEEK_OFF : DayStatus.UNMARKED;
      assertEquals(expected, r.status());
    }
    if (stored != null && (c.leave() == null || !working)) {
      assertEquals(DayStatusResolver.Source.MARK, r.source(), "a stored mark beats derived days");
    }
    if (r.status() == DayStatus.UNMARKED) {
      assertTrue(stored == null && working && c.leave() == null);
    }
  }

  @Provide
  Arbitrary<Case> cases() {
    Arbitrary<LocalDate> date = Arbitraries.integers().between(0, 400).map(BASE::plusDays);
    Arbitrary<String> join = Arbitraries.integers().between(-30, 300)
        .map(d -> HrDateUtils.format(BASE.plusDays(d))).injectNull(0.2);
    Arbitrary<String> exit = Arbitraries.integers().between(100, 500)
        .map(d -> HrDateUtils.format(BASE.plusDays(d))).injectNull(0.6);
    Arbitrary<AttendanceDay> mark = Combinators.combine(
            Arbitraries.of(AttendanceStatus.class),
            Arbitraries.of("", "08:00", "09:10", "09:30", "13:00").injectNull(0.3),
            Arbitraries.of("", "12:00", "18:00", "22:00").injectNull(0.3))
        .as((s, in, out) -> {
          AttendanceDay d = new AttendanceDay();
          d.setStatus(s);
          d.setInTime(in == null || in.isEmpty() ? null : in);
          d.setOutTime(out == null || out.isEmpty() ? null : out);
          return d;
        }).injectNull(0.4);
    Arbitrary<LeaveRequest> leave = Arbitraries.of(true, false).map(half -> {
      LeaveRequest l = new LeaveRequest();
      l.setId("l");
      l.setHalfDay(half);
      return l;
    }).injectNull(0.5);
    Arbitrary<HrSettings> settings = Combinators.combine(
            Arbitraries.of(DayOfWeek.class).set().ofMinSize(1).ofMaxSize(3),
            Arbitraries.of(DayOfWeek.class).set().ofMinSize(1).ofMaxSize(2),
            Arbitraries.of("2025-03", "2025-06", "2025-11"),
            Arbitraries.of(true, false))
        .as((first, later, from, overtime) -> {
          HrSettings s = HrSettingsService.withDefaults(null, SHOP);
          List<HrSettings.WeeklyOffEntry> schedule = new ArrayList<>();
          schedule.add(new HrSettings.WeeklyOffEntry("0000-01", sorted(first)));
          schedule.add(new HrSettings.WeeklyOffEntry(from, sorted(later)));
          s.setWeeklyOffSchedule(schedule);
          s.setOvertimeEnabled(overtime);
          return s;
        });
    return Combinators.combine(date, join, exit, mark, leave, Arbitraries.of(true, false),
            settings)
        .as((d, j, x, m, l, h, s) -> {
          Employee e = new Employee();
          e.setId("e");
          e.setJoinDate(j);
          e.setExitDate(x);
          return new Case(d, e, m, l, h, s);
        });
  }

  private static List<DayOfWeek> sorted(Set<DayOfWeek> days) {
    return days.stream().sorted().toList();
  }
}
