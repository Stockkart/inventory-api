package com.inventory.hr.service.payroll;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.PayrollLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything {@link PayrollCalculator} needs for one period, read in bulk before the calculation
 * (design "Endpoint budgets"): no repository is touched while calculating.
 *
 * @param period the payroll month
 * @param settings effective HR settings (defaults filled in)
 * @param holidays shop holidays in the month, {@code yyyy-MM-dd}
 * @param leaveTypePaid leave type id → paid; a type missing here counts as unpaid
 * @param employees one entry per employee employed at some point in the month
 * @param today the shop's today (Asia/Kolkata), set by {@code PayrollInputReader} so the
 *     calculator stays pure; days after it have not happened yet (run B7). Null = every day of
 *     the month has happened.
 */
public record PayrollInput(
    YearMonth period,
    HrSettings settings,
    Set<String> holidays,
    Map<String, Boolean> leaveTypePaid,
    List<EmployeeInput> employees,
    LocalDate today) {

  /** A snapshot with no {@code today}: every day of the month counts as happened. */
  public PayrollInput(YearMonth period, HrSettings settings, Set<String> holidays,
      Map<String, Boolean> leaveTypePaid, List<EmployeeInput> employees) {
    this(period, settings, holidays, leaveTypePaid, employees, null);
  }

  /**
   * One employee's inputs.
   *
   * @param employee profile with {@code salaryRevisions}, join/exit dates and working hours
   * @param marks stored days of the month by {@code yyyy-MM-dd}
   * @param leave approved leave by {@code yyyy-MM-dd} (at most one per date)
   * @param manualItems bonus / other-deduction lines kept across recalculation; may be empty
   * @param advanceOverride this month's advance deduction set in review; null = automatic
   * @param advances ACTIVE advances with something still owed; may be empty
   */
  public record EmployeeInput(
      Employee employee,
      Map<String, AttendanceDay> marks,
      Map<String, LeaveRequest> leave,
      List<PayrollLine.ManualItem> manualItems,
      BigDecimal advanceOverride,
      List<AdvanceBalance> advances) {}

  /**
   * An ACTIVE advance as recovery sees it.
   *
   * @param date {@code yyyy-MM-dd}, the FIFO order (R7.3)
   * @param outstanding amount − recovered by FINALISED runs, ≥ 0
   */
  public record AdvanceBalance(String advanceId, String date, BigDecimal monthlyDeduction,
      BigDecimal outstanding) {}
}
