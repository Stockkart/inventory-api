package com.inventory.hr.service.payroll;

import static com.inventory.hr.service.payroll.PayrollTestFixtures.PAID_TYPE;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.UNPAID_TYPE;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.employee;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.leave;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.leaveTypes;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.mark;
import static com.inventory.hr.service.payroll.PayrollTestFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DeductionKind;
import com.inventory.hr.domain.model.enums.EarningKind;
import com.inventory.hr.domain.model.enums.ManualItemKind;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PerDayBasis;
import com.inventory.hr.service.HrAccountingPoster;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Formulas and the design's worked example (design "Payroll calculation", R8.3–R8.10). */
class PayrollCalculatorTest {

  private static final YearMonth APRIL = YearMonth.of(2025, 4);
  private static final Set<String> APRIL_HOLIDAY = Set.of("2025-04-14");

  /**
   * April 2025 as in the design: Sundays off, holiday 14th; present 21 (three of them 09:00–23:00,
   * 120 min overtime each), half day 17th and 18th, paid leave 15th, absent 16th.
   */
  private static Map<String, AttendanceDay> aprilMarks(String id, boolean withOvertime) {
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = APRIL.atDay(1); !d.isAfter(APRIL.atEndOfMonth()); d = d.plusDays(1)) {
      String date = d.toString();
      if (d.getDayOfWeek() == DayOfWeek.SUNDAY || APRIL_HOLIDAY.contains(date)
          || date.equals("2025-04-15")) {
        continue;
      }
      AttendanceStatus status = switch (date) {
        case "2025-04-16" -> AttendanceStatus.ABSENT;
        case "2025-04-17", "2025-04-18" -> AttendanceStatus.HALF_DAY;
        default -> AttendanceStatus.PRESENT;
      };
      boolean overtime = withOvertime && Set.of("2025-04-01", "2025-04-02", "2025-04-03")
          .contains(date);
      marks.put(date, mark(id, date, status, overtime ? "09:00" : null,
          overtime ? "23:00" : null));
    }
    return marks;
  }

  private static PayrollInput input(HrSettings s, YearMonth month, Set<String> holidays,
      PayrollInput.EmployeeInput... employees) {
    return new PayrollInput(month, s, holidays, leaveTypes(), List.of(employees));
  }

  private static PayrollLine calc(PayrollInput input) {
    return PayrollCalculator.calculate(input, input.employees().get(0)).line();
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
  }

  private static BigDecimal earning(PayrollLine line, EarningKind kind) {
    return line.getEarnings().stream().filter(e -> e.getKind() == kind)
        .map(PayrollLine.EarningItem::getAmount).findFirst().orElse(BigDecimal.ZERO);
  }

  @Test
  void theDesignsWorkedExampleAddsUpToTheRupee() {
    Employee ravi = employee("1", PayType.MONTHLY, "14500", "2024-04-01", null);
    PayrollInput in = input(settings(PerDayBasis.CALENDAR_DAYS, true), APRIL, APRIL_HOLIDAY,
        new PayrollInput.EmployeeInput(ravi, aprilMarks("1", true),
            Map.of("2025-04-15", leave("1", "2025-04-15", PAID_TYPE, false)),
            List.of(new PayrollLine.ManualItem(ManualItemKind.BONUS, "Diwali",
                new BigDecimal("250"))),
            null,
            List.of(new PayrollInput.AdvanceBalance("adv-1", "2025-03-01",
                new BigDecimal("1000"), new BigDecimal("3000")))));

    PayrollLine line = calc(in);

    PayrollLine.DayCounts c = line.getDayCounts();
    assertMoney("21", c.getPresent());
    assertEquals(2, c.getHalfDays());
    assertMoney("1", c.getPaidLeave());
    assertMoney("1", c.getAbsent());
    assertMoney("4", c.getWeekOff());
    assertMoney("1", c.getHoliday());
    assertMoney("28", c.getPaidDays());
    assertEquals(30, c.getBasisDays());
    assertEquals(360, line.getOvertimeMinutes());
    // 14,500 × 28 ÷ 30 = 13,533.333 → 13,533.33
    assertMoney("13533.33", earning(line, EarningKind.BASE));
    // 14,500 × 360 × 1.5 ÷ (30 × 720)
    assertMoney("362.50", earning(line, EarningKind.OVERTIME));
    assertMoney("250.00", earning(line, EarningKind.BONUS));
    assertMoney("14145.83", line.getGross());
    assertMoney("1000.00", line.getTotalDeductions());
    assertMoney("13145.83", line.getNetBeforeRounding());
    assertMoney("0.17", line.getRounding());
    assertMoney("13146.00", line.getNet());
    assertMoney("2000", line.getAdvanceOutstandingAfter());
    assertEquals(List.of(new PayrollLine.AdvanceRecovery("adv-1", new BigDecimal("1000"))),
        line.getAdvanceRecoveries());
    assertTrue(line.getWarnings().isEmpty());

    // journal: Dr 5700 14,145.83 + Dr 5500 0.17 = Cr 2400 13,146.00 + Cr 1250 1,000.00
    HrAccountingPoster.RunTotals t = HrAccountingPoster.runTotals(List.of(line));
    assertMoney("14145.83", t.expense());
    assertMoney("1000", t.recovered());
    assertEquals(0, t.expense().add(t.rounding()).compareTo(t.net().add(t.recovered())));
  }

  @Test
  void theDailyWageHelperIsPaidForPresentHalfDaysPaidLeaveAndHolidaysOnly() {
    Employee helper = employee("2", PayType.DAILY, "600", "2024-04-01", null);
    PayrollInput in = input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL, APRIL_HOLIDAY,
        new PayrollInput.EmployeeInput(helper, aprilMarks("2", false),
            Map.of("2025-04-15", leave("2", "2025-04-15", PAID_TYPE, false)), List.of(), null,
            List.of()));

    PayrollLine line = calc(in);

    // 21 + 1 + 1 + 1 = 24 days × 600; week-offs unpaid for daily wages
    assertMoney("24", line.getDayCounts().getPaidDays());
    assertMoney("14400.00", line.getGross());
    assertMoney("14400.00", line.getNet());
    assertMoney("0", line.getRounding());
    assertNull(line.getAdvanceOutstandingAfter());
  }

  @Test
  void aFixedBasisPaysAWholeMonthEmployeeTheBasisLessLossOfPayDays() {
    // May 2025 has 31 days; one absence and one unpaid leave day
    YearMonth may = YearMonth.of(2025, 5);
    Employee e = employee("3", PayType.MONTHLY, "26000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = may.atDay(1); !d.isAfter(may.atEndOfMonth()); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !d.toString().equals("2025-05-07")) {
        marks.put(d.toString(), mark("3", d.toString(), d.toString().equals("2025-05-06")
            ? AttendanceStatus.ABSENT : AttendanceStatus.PRESENT, null, null));
      }
    }
    PayrollInput in = input(settings(PerDayBasis.FIXED_26, false), may, Set.of(),
        new PayrollInput.EmployeeInput(e, marks,
            Map.of("2025-05-07", leave("3", "2025-05-07", UNPAID_TYPE, false)), List.of(),
            null, List.of()));

    PayrollLine line = calc(in);

    assertMoney("24", line.getDayCounts().getPaidDays());
    assertEquals(26, line.getDayCounts().getBasisDays());
    assertMoney("24000.00", line.getGross());
  }

  @Test
  void aFixedBasisPartMonthIsPaidTheCountedDaysButNeverMoreThanTheBasis() {
    YearMonth may = YearMonth.of(2025, 5);
    // joined on the 2nd: 30 employed days, all paid → capped at 26
    Employee e = employee("4", PayType.MONTHLY, "26000", "2025-05-02", null);
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = may.atDay(2); !d.isAfter(may.atEndOfMonth()); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        marks.put(d.toString(), mark("4", d.toString(), AttendanceStatus.PRESENT, null, null));
      }
    }
    PayrollLine capped = calc(input(settings(PerDayBasis.FIXED_26, false), may, Set.of(),
        new PayrollInput.EmployeeInput(e, marks, Map.of(), List.of(), null, List.of())));
    assertEquals(30, capped.getDayCounts().getEmployedDays());
    assertMoney("26", capped.getDayCounts().getPaidDays());
    assertMoney("26000.00", capped.getGross());

    // left on the 10th under FIXED_30: 10 counted days (9 present + 1 Sunday)
    Employee leaver = employee("5", PayType.MONTHLY, "30000", "2024-01-01", "2025-05-10");
    PayrollLine part = calc(input(settings(PerDayBasis.FIXED_30, false), may, Set.of(),
        new PayrollInput.EmployeeInput(leaver, presentUntil("5", may, 10), Map.of(), List.of(),
            null, List.of())));
    assertEquals(10, part.getDayCounts().getEmployedDays());
    assertMoney("10", part.getDayCounts().getPaidDays());
    assertMoney("10000.00", part.getGross());
  }

  @Test
  void aMissingJoinDateCountsFromTheCreatedDateNotTheStartOfTheMonth() {
    YearMonth oct = YearMonth.of(2026, 10);
    Employee e = employee("6", PayType.MONTHLY, "30000", null, null);
    // 26 Oct 00:30 in Asia/Kolkata
    e.setCreatedAt(java.time.Instant.parse("2026-10-25T19:00:00Z"));
    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), oct, Set.of(),
        new PayrollInput.EmployeeInput(e, Map.of(), Map.of(), List.of(), null, List.of())));
    // 26–31 Oct (no Sunday): 6 employed, all unmarked; 1–25 Oct neither unmarked nor week-off
    assertEquals(6, line.getDayCounts().getEmployedDays());
    assertMoney("6", line.getDayCounts().getUnmarked());
    assertMoney("0", line.getDayCounts().getWeekOff());
    assertMoney("0", line.getDayCounts().getPaidDays());
    assertEquals("2026-10-26", line.getEmployeeSnapshot().getJoinDate());
  }

  private static Map<String, AttendanceDay> presentUntil(String id, YearMonth month, int day) {
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (int i = 1; i <= day; i++) {
      LocalDate d = month.atDay(i);
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        marks.put(d.toString(), mark(id, d.toString(), AttendanceStatus.PRESENT, null, null));
      }
    }
    return marks;
  }

  @Test
  void workOnAWeekOffPaysADayExtraOnlyForMonthlyStaffWithOvertimeOn() {
    Employee e = employee("6", PayType.MONTHLY, "30000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("6", false);
    marks.put("2025-04-06", mark("6", "2025-04-06", AttendanceStatus.PRESENT, null, null));
    PayrollInput.EmployeeInput in = new PayrollInput.EmployeeInput(e, marks,
        Map.of("2025-04-15", leave("6", "2025-04-15", PAID_TYPE, false)), List.of(), null,
        List.of());

    PayrollLine on = calc(input(settings(PerDayBasis.CALENDAR_DAYS, true), APRIL, APRIL_HOLIDAY,
        in));
    PayrollLine off = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, in));

    assertMoney("1", on.getDayCounts().getOffDayWorked());
    assertMoney("1000.00", earning(on, EarningKind.OFF_DAY_WORK));
    assertMoney("0", earning(off, EarningKind.OFF_DAY_WORK));
    // the Sunday stays one of April's 4 paid week-offs; the work on it is offDayWorked
    assertMoney("4", on.getDayCounts().getWeekOff());
    assertMoney("21", on.getDayCounts().getPresent());
    assertMoney(on.getDayCounts().getPaidDays().toPlainString(), off.getDayCounts().getPaidDays());
  }

  @Test
  void aDailyWageHelperWorkingTheHolidayGetsTheHolidayAndTheDayWorked() {
    Employee helper = employee("12", PayType.DAILY, "600", "2024-04-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("12", false);
    marks.put("2025-04-14", mark("12", "2025-04-14", AttendanceStatus.PRESENT, null, null));

    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, new PayrollInput.EmployeeInput(helper, marks,
            Map.of("2025-04-15", leave("12", "2025-04-15", PAID_TYPE, false)), List.of(), null,
            List.of())));

    // R5.5: 24 days as before + 1 for the work on the holiday, which itself stays paid
    assertMoney("1", line.getDayCounts().getHoliday());
    assertMoney("1", line.getDayCounts().getOffDayWorked());
    assertMoney("25", line.getDayCounts().getPaidDays());
    assertMoney("15000.00", line.getGross());
  }

  @Test
  void aDailyWageHelperIsPaidTheWorkDoneOnWeekOffs() {
    Employee helper = employee("13", PayType.DAILY, "600", "2024-04-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("13", false);
    marks.put("2025-04-06", mark("13", "2025-04-06", AttendanceStatus.PRESENT, null, null));
    marks.put("2025-04-13", mark("13", "2025-04-13", AttendanceStatus.HALF_DAY, null, null));

    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, true), APRIL,
        APRIL_HOLIDAY, new PayrollInput.EmployeeInput(helper, marks,
            Map.of("2025-04-15", leave("13", "2025-04-15", PAID_TYPE, false)), List.of(), null,
            List.of())));

    // unworked Sundays stay unpaid; 1 + 0.5 worked on two of them is paid
    assertMoney("4", line.getDayCounts().getWeekOff());
    assertMoney("1.5", line.getDayCounts().getOffDayWorked());
    assertEquals(2, line.getDayCounts().getHalfDays());
    assertMoney("25.5", line.getDayCounts().getPaidDays());
    assertMoney("15300.00", line.getGross());
    // daily wage pays off-day work in base pay, never as a separate earning
    assertMoney("0", earning(line, EarningKind.OFF_DAY_WORK));
  }

  @Test
  void aHalfDayOnAWeekOffNeverLowersMonthlyPayAndPaysHalfADayExtraWithOvertimeOn() {
    Employee e = employee("14", PayType.MONTHLY, "30000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("14", false);
    marks.put("2025-04-06", mark("14", "2025-04-06", AttendanceStatus.HALF_DAY, null, null));
    PayrollInput.EmployeeInput in = new PayrollInput.EmployeeInput(e, marks,
        Map.of("2025-04-15", leave("14", "2025-04-15", PAID_TYPE, false)), List.of(), null,
        List.of());

    PayrollLine on = calc(input(settings(PerDayBasis.CALENDAR_DAYS, true), APRIL, APRIL_HOLIDAY,
        in));
    PayrollLine off = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, in));

    for (PayrollLine line : List.of(on, off)) {
      // 21 present + 2 × ½ + 1 paid leave + 4 week-offs + 1 holiday, as with no mark on the 6th
      assertMoney("28", line.getDayCounts().getPaidDays());
      assertMoney("4", line.getDayCounts().getWeekOff());
      assertEquals(2, line.getDayCounts().getHalfDays());
      assertMoney("0.5", line.getDayCounts().getOffDayWorked());
      assertMoney("28000.00", earning(line, EarningKind.BASE));
    }
    // 30,000 × 0.5 ÷ 30
    assertMoney("500.00", earning(on, EarningKind.OFF_DAY_WORK));
    assertMoney("0", earning(off, EarningKind.OFF_DAY_WORK));
  }

  @Test
  void aFixedBasisWholeMonthKeepsItsOffDaysWhateverIsMarkedOnThem() {
    YearMonth may = YearMonth.of(2025, 5);
    Employee e = employee("15", PayType.MONTHLY, "26000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = may.atDay(1); !d.isAfter(may.atEndOfMonth()); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !d.toString().equals("2025-05-07")) {
        marks.put(d.toString(), mark("15", d.toString(), d.toString().equals("2025-05-06")
            ? AttendanceStatus.ABSENT : AttendanceStatus.PRESENT, null, null));
      }
    }
    // worked Sunday 4th, half of Sunday 18th; an Absent stored on Sunday 11th is just the off day
    marks.put("2025-05-04", mark("15", "2025-05-04", AttendanceStatus.PRESENT, null, null));
    marks.put("2025-05-18", mark("15", "2025-05-18", AttendanceStatus.HALF_DAY, null, null));
    marks.put("2025-05-11", mark("15", "2025-05-11", AttendanceStatus.ABSENT, null, null));
    PayrollInput.EmployeeInput in = new PayrollInput.EmployeeInput(e, marks,
        Map.of("2025-05-07", leave("15", "2025-05-07", UNPAID_TYPE, false)), List.of(), null,
        List.of());

    PayrollLine off = calc(input(settings(PerDayBasis.FIXED_26, false), may, Set.of(), in));
    PayrollLine on = calc(input(settings(PerDayBasis.FIXED_26, true), may, Set.of(), in));

    for (PayrollLine line : List.of(on, off)) {
      // 26 − (1 absent + 1 unpaid leave): the off-day marks create no loss of pay
      assertMoney("24", line.getDayCounts().getPaidDays());
      assertMoney("1", line.getDayCounts().getAbsent());
      assertMoney("4", line.getDayCounts().getWeekOff());
      assertMoney("1.5", line.getDayCounts().getOffDayWorked());
      assertMoney("24000.00", earning(line, EarningKind.BASE));
    }
    // 26,000 × 1.5 ÷ 26
    assertMoney("1500.00", earning(on, EarningKind.OFF_DAY_WORK));
    assertMoney("25500.00", on.getGross());
    assertMoney("24000.00", off.getGross());
  }

  @Test
  void aHolidayStoredOnAWeekOffIsPaidAsAHolidayToADailyWageHelper() {
    Employee helper = employee("16", PayType.DAILY, "600", "2024-04-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("16", false);
    marks.put("2025-04-06", mark("16", "2025-04-06", AttendanceStatus.HOLIDAY, null, null));

    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, new PayrollInput.EmployeeInput(helper, marks,
            Map.of("2025-04-15", leave("16", "2025-04-15", PAID_TYPE, false)), List.of(), null,
            List.of())));

    // the shop's holiday on the 14th + the override on Sunday the 6th; 3 Sundays stay unpaid
    assertMoney("2", line.getDayCounts().getHoliday());
    assertMoney("3", line.getDayCounts().getWeekOff());
    assertMoney("25", line.getDayCounts().getPaidDays());
  }

  @Test
  void aHalfDayLeaveCountsHalfLeaveAndHalfOfTheMark() {
    Employee e = employee("7", PayType.MONTHLY, "30000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("7", false);
    marks.put("2025-04-15", mark("7", "2025-04-15", AttendanceStatus.PRESENT, null, null));
    LeaveRequest half = leave("7", "2025-04-15", UNPAID_TYPE, true);

    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, new PayrollInput.EmployeeInput(e, marks, Map.of("2025-04-15", half),
            List.of(), null, List.of())));

    assertMoney("21.5", line.getDayCounts().getPresent());
    assertMoney("0.5", line.getDayCounts().getUnpaidLeave());
    assertMoney("0", line.getDayCounts().getUnmarked());
  }

  @Test
  void unmarkedDaysAreListedAndUnpaid() {
    Employee e = employee("8", PayType.MONTHLY, "30000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = aprilMarks("8", false);
    marks.remove("2025-04-22");
    PayrollInput in = input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL, APRIL_HOLIDAY,
        new PayrollInput.EmployeeInput(e, marks,
            Map.of("2025-04-15", leave("8", "2025-04-15", PAID_TYPE, false)), List.of(), null,
            List.of()));

    PayrollCalculator.Result result = PayrollCalculator.calculate(in, in.employees().get(0));

    assertEquals(List.of("2025-04-22"), result.unmarkedDates());
    assertMoney("27", result.line().getDayCounts().getPaidDays());
  }

  /** April marked up to {@code today} only, the 8th left unmarked, paid leave 15th (run B7). */
  private static PayrollInput aprilSoFar(String id, PerDayBasis basis, LocalDate today,
      Map<String, LeaveRequest> extraLeave) {
    Employee e = employee(id, PayType.MONTHLY, "30000", "2024-01-01", null);
    Map<String, AttendanceDay> marks = aprilMarks(id, false);
    marks.keySet().removeIf(d -> LocalDate.parse(d).isAfter(today));
    marks.remove("2025-04-08");
    Map<String, LeaveRequest> leave = new HashMap<>(extraLeave);
    leave.put("2025-04-15", leave(id, "2025-04-15", PAID_TYPE, false));
    return new PayrollInput(APRIL, settings(basis, false), APRIL_HOLIDAY, leaveTypes(),
        List.of(new PayrollInput.EmployeeInput(e, marks, leave, List.of(), null, List.of())),
        today);
  }

  @Test
  void daysAfterTodayAreNeitherUnmarkedNorPaidInACurrentMonthDraft() {
    LocalDate today = LocalDate.of(2025, 4, 20);
    for (PerDayBasis basis : List.of(PerDayBasis.CALENDAR_DAYS, PerDayBasis.FIXED_30)) {
      PayrollInput in = aprilSoFar("10", basis, today, Map.of());

      PayrollCalculator.Result result = PayrollCalculator.calculate(in, in.employees().get(0));

      // 21–26 and 28–30 April are not yet: only the 8th is unmarked
      assertEquals(List.of("2025-04-08"), result.unmarkedDates(), basis.name());
      PayrollLine.DayCounts c = result.line().getDayCounts();
      assertMoney("1", c.getUnmarked());
      assertMoney("11", c.getPresent());
      // so far: 11 present + 2 × ½ + 1 paid leave + 4 Sundays + 1 holiday; FIXED_30 gives the
      // same: 30 − (1 absent + 1 from half days + 1 unmarked + 9 not yet)
      assertMoney("18", c.getPaidDays());
      assertEquals(30, c.getEmployedDays());
    }
  }

  @Test
  void theUnmarkedHalfOfAFutureHalfDayLeaveIsNotUnmarked() {
    LocalDate today = LocalDate.of(2025, 4, 20);
    PayrollInput in = aprilSoFar("11", PerDayBasis.FIXED_30, today,
        Map.of("2025-04-25", leave("11", "2025-04-25", UNPAID_TYPE, true)));

    PayrollCalculator.Result result = PayrollCalculator.calculate(in, in.employees().get(0));

    assertEquals(List.of("2025-04-08"), result.unmarkedDates());
    assertMoney("1", result.line().getDayCounts().getUnmarked());
    assertMoney("0.5", result.line().getDayCounts().getUnpaidLeave());
    // 30 − (1 absent + 1 half days + 0.5 unpaid leave + 1 unmarked + 8.5 not yet)
    assertMoney("18", result.line().getDayCounts().getPaidDays());
  }

  @Test
  void aMonthThatIsOverCountsEveryUnmarkedDayAsBefore() {
    for (LocalDate today : List.of(LocalDate.of(2025, 4, 30), LocalDate.of(2025, 6, 1))) {
      Employee e = employee("12", PayType.MONTHLY, "30000", "2024-01-01", null);
      Map<String, AttendanceDay> marks = aprilMarks("12", false);
      marks.remove("2025-04-22");
      Map<String, LeaveRequest> leave =
          Map.of("2025-04-15", leave("12", "2025-04-15", PAID_TYPE, false));
      PayrollInput.EmployeeInput emp =
          new PayrollInput.EmployeeInput(e, marks, leave, List.of(), null, List.of());
      PayrollInput withToday = new PayrollInput(APRIL, settings(PerDayBasis.CALENDAR_DAYS,
          false), APRIL_HOLIDAY, leaveTypes(), List.of(emp), today);
      PayrollInput without = input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
          APRIL_HOLIDAY, emp);

      PayrollCalculator.Result result = PayrollCalculator.calculate(withToday, emp);

      assertEquals(List.of("2025-04-22"), result.unmarkedDates(), today.toString());
      assertMoney("27", result.line().getDayCounts().getPaidDays());
      assertEquals(PayrollCalculator.calculate(without, emp).line(), result.line());
    }
  }

  @Test
  void noSalaryRevisionGivesAZeroLineWithAWarning() {
    Employee e = employee("9", PayType.MONTHLY, null, "2024-01-01", null);
    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL,
        APRIL_HOLIDAY, new PayrollInput.EmployeeInput(e, aprilMarks("9", false), Map.of(),
            List.of(), null, List.of(new PayrollInput.AdvanceBalance("a", "2025-01-01",
                new BigDecimal("100"), new BigDecimal("100"))))));

    assertEquals(List.of(PayrollCalculator.NO_SALARY), line.getWarnings());
    assertMoney("0", line.getGross());
    assertMoney("0", line.getNet());
    assertTrue(line.getAdvanceRecoveries().isEmpty(), "nothing to recover from zero pay");
  }

  @Test
  void deductionsAboveGrossAreFlaggedAndAdvanceRecoveryStopsAtZero() {
    Employee e = employee("10", PayType.DAILY, "100", "2024-01-01", null);
    Map<String, AttendanceDay> marks = Map.of("2025-04-01",
        mark("10", "2025-04-01", AttendanceStatus.PRESENT, null, null));
    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL, Set.of(),
        new PayrollInput.EmployeeInput(e, marks, Map.of(),
            List.of(new PayrollLine.ManualItem(ManualItemKind.OTHER_DEDUCTION, null,
                new BigDecimal("150"))),
            null, List.of(new PayrollInput.AdvanceBalance("a", "2025-01-01",
                new BigDecimal("100"), new BigDecimal("100"))))));

    assertTrue(line.getWarnings().contains(PayrollCalculator.DEDUCTIONS_EXCEED_PAY));
    assertTrue(line.getDeductions().stream().noneMatch(d -> d.getKind() == DeductionKind.ADVANCE));
    assertMoney("-50.00", line.getNetBeforeRounding());
  }

  @Test
  void netIsRoundedDownBelowHalfARupee() {
    Employee e = employee("11", PayType.DAILY, "100.40", "2024-01-01", null);
    PayrollLine line = calc(input(settings(PerDayBasis.CALENDAR_DAYS, false), APRIL, Set.of(),
        new PayrollInput.EmployeeInput(e, Map.of("2025-04-01",
            mark("11", "2025-04-01", AttendanceStatus.PRESENT, null, null)), Map.of(), List.of(),
            null, List.of())));
    assertMoney("100.00", line.getNet());
    assertMoney("-0.40", line.getRounding());
  }

  @Test
  void anOverrideReplacesTheAutomaticAmountAndIsTakenOldestFirst() {
    PayrollCalculator.Allocation a = PayrollCalculator.allocate(List.of(
        new PayrollInput.AdvanceBalance("new", "2025-03-01", new BigDecimal("100"),
            new BigDecimal("500")),
        new PayrollInput.AdvanceBalance("old", "2025-01-01", new BigDecimal("100"),
            new BigDecimal("300"))), new BigDecimal("450"), new BigDecimal("10000"));

    assertMoney("450", a.total());
    assertEquals("old", a.recoveries().get(0).getAdvanceId());
    assertMoney("300", a.recoveries().get(0).getAmount());
    assertMoney("150", a.recoveries().get(1).getAmount());
  }

  @Test
  void aSalaryMonthStartingOnThe26thRunsFrom26SepTo25OctOnA30DayBasis() {
    HrSettings s = settings(PerDayBasis.CALENDAR_DAYS, false);
    s.setPayCycleStartDay(26);
    Employee e = employee("1", PayType.MONTHLY, "15000", "2024-04-01", null);
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = LocalDate.of(2026, 9, 26); !d.isAfter(LocalDate.of(2026, 10, 25));
        d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        String date = d.toString();
        marks.put(date, mark("1", date, date.equals("2026-10-06") ? AttendanceStatus.ABSENT
            : AttendanceStatus.PRESENT, null, null));
      }
    }
    // a mark outside the cycle (26 Oct is November's) is never counted
    marks.put("2026-10-26", mark("1", "2026-10-26", AttendanceStatus.ABSENT, null, null));

    PayrollInput.EmployeeInput emp =
        new PayrollInput.EmployeeInput(e, marks, Map.of(), List.of(), null, List.of());
    PayrollCalculator.Result result = PayrollCalculator.calculate(
        input(s, YearMonth.of(2026, 10), Set.of(), emp), emp);
    PayrollLine line = result.line();

    assertEquals("2026-10", line.getPeriod());
    assertEquals("2026-09-26", line.getFromDate());
    assertEquals("2026-10-25", line.getToDate());
    PayrollLine.DayCounts c = line.getDayCounts();
    assertEquals(30, c.getEmployedDays());
    assertEquals(30, c.getBasisDays());
    // Sundays 27 Sep, 4, 11, 18, 25 Oct; 24 present, 1 absent
    assertMoney("5", c.getWeekOff());
    assertMoney("24", c.getPresent());
    assertMoney("1", c.getAbsent());
    assertMoney("0", c.getUnmarked());
    assertMoney("29", c.getPaidDays());
    assertTrue(result.unmarkedDates().isEmpty());
    // 15,000 × 29 ÷ 30
    assertMoney("14500.00", earning(line, EarningKind.BASE));
  }

  @Test
  void withAStartDayTheWholeMonthRuleMeansEveryDayOfTheCycle() {
    HrSettings s = settings(PerDayBasis.FIXED_30, false);
    s.setPayCycleStartDay(26);
    // joined 1 Oct: employed 25 of the 30 days of 26 Sep – 25 Oct, so not a whole month
    Employee e = employee("1", PayType.MONTHLY, "15000", "2026-10-01", null);
    Map<String, AttendanceDay> marks = new HashMap<>();
    for (LocalDate d = LocalDate.of(2026, 10, 1); !d.isAfter(LocalDate.of(2026, 10, 25));
        d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
        marks.put(d.toString(), mark("1", d.toString(), AttendanceStatus.PRESENT, null, null));
      }
    }
    PayrollInput.EmployeeInput emp =
        new PayrollInput.EmployeeInput(e, marks, Map.of(), List.of(), null, List.of());

    PayrollLine line = PayrollCalculator.calculate(
        input(s, YearMonth.of(2026, 10), Set.of(), emp), emp).line();

    assertEquals(25, line.getDayCounts().getEmployedDays());
    assertEquals(30, line.getDayCounts().getBasisDays());
    // counted days (21 present + 4 Sundays), not basis − loss of pay
    assertMoney("25", line.getDayCounts().getPaidDays());
  }

  @Test
  void basisDaysFollowTheSetting() {
    assertEquals(28, PayrollCalculator.basisDays(PerDayBasis.CALENDAR_DAYS, YearMonth.of(2025, 2)));
    assertEquals(30, PayrollCalculator.basisDays(PerDayBasis.FIXED_30, YearMonth.of(2025, 2)));
    assertEquals(26, PayrollCalculator.basisDays(PerDayBasis.FIXED_26, YearMonth.of(2025, 2)));
    assertEquals(31, PayrollCalculator.basisDays(null, YearMonth.of(2025, 1)));
  }
}
