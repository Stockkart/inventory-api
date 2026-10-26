package com.inventory.hr.service;

import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.service.payroll.PayrollInput;
import com.inventory.hr.utils.HrDateUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Bulk reads behind a payroll calculation (design "Endpoint budgets", {@code POST /payroll-runs}
 * and {@code /recalculate}): settings, holidays, employees employed, days of the month, approved
 * leave, leave types, ACTIVE advances, one recoveries aggregation and the run's existing lines —
 * nine queries, each collection read once with {@code $in} or a range and a projection, all with
 * the 10 s month-wide {@code maxTimeMS}. Callers wrap the call in {@link HrQueryRunner#read}.
 */
@Component
public class PayrollInputReader {

  static final long MAX_TIME_MS = HrQueryRunner.MONTH_MAX_TIME_MS;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private HrSettingsService settingsService;

  @Autowired private AttendanceService attendanceService;

  @Autowired private AdvanceService advanceService;

  /**
   * The snapshot and the run's lines as stored before this calculation.
   *
   * @param existingLines by employee id (manual items, override, ids, {@code updatedAt})
   */
  public record Snapshot(PayrollInput input, Map<String, PayrollLine> existingLines) {}

  /**
   * @param runId the run whose existing lines give manual items and overrides; null = none
   * @param finalisedRunIds FINALISED runs of the shop, for advance outstanding
   * @param onlyEmployees restrict to these employees; null = everyone employed in the month
   * @param withPay false = attendance only (unmarked list): no leave types, advances or lines
   */
  public Snapshot read(String shopId, YearMonth month, String runId,
      Collection<String> finalisedRunIds, Set<String> onlyEmployees, boolean withPay) {
    String from = HrDateUtils.format(month.atDay(1));
    String to = HrDateUtils.format(month.atEndOfMonth());
    // read here, not in the calculator, so the calculation stays pure (run B7)
    LocalDate today = HrDateUtils.today();
    // 1–5: what the day resolver needs
    HrSettings settings = settingsService.effectiveSettings(shopId);
    Set<String> holidays = attendanceService.holidayDates(shopId, from, to, MAX_TIME_MS);
    List<Employee> employees = employees(shopId, from, to, onlyEmployees, withPay);
    if (employees.isEmpty()) {
      return new Snapshot(new PayrollInput(month, settings, holidays, Map.of(), List.of(), today),
          Map.of());
    }
    List<String> ids = employees.stream().map(Employee::getId).toList();
    Map<String, Map<String, AttendanceDay>> marks = new HashMap<>();
    for (AttendanceDay d : days(shopId, onlyEmployees != null ? ids : null, from, to)) {
      marks.computeIfAbsent(d.getEmployeeId(), k -> new HashMap<>()).put(d.getDate(), d);
    }
    Map<String, Map<String, LeaveRequest>> leave = new HashMap<>();
    List<LeaveRequest> approved = attendanceService.approvedLeave(shopId,
        onlyEmployees != null ? ids : null, from, to, MAX_TIME_MS);
    AttendanceService.leaveByEmployeeDate(approved, from, to).forEach((key, l) ->
        leave.computeIfAbsent(l.getEmployeeId(), k -> new HashMap<>())
            .put(key.substring(key.indexOf('|') + 1), l));

    Map<String, Boolean> paidTypes = new HashMap<>();
    Map<String, List<PayrollInput.AdvanceBalance>> advances = new HashMap<>();
    Map<String, PayrollLine> existing = new HashMap<>();
    if (withPay) {
      // 6. leave types: paid or not
      Query types = Query.query(Criteria.where("shopId").is(shopId));
      types.fields().include("paid");
      types.maxTimeMsec(MAX_TIME_MS);
      for (LeaveType t : mongoTemplate.find(types, LeaveType.class)) {
        paidTypes.put(t.getId(), Boolean.TRUE.equals(t.getPaid()));
      }
      // 7–8. ACTIVE advances given by the month end, and what FINALISED runs recovered of them
      List<Advance> active = activeAdvances(shopId, ids, to);
      Map<String, BigDecimal> recovered = advanceService.recoveredByAdvance(shopId,
          active.stream().map(Advance::getId).toList(), finalisedRunIds);
      for (Advance a : active) {
        BigDecimal owed = AdvanceService.outstanding(a, recovered);
        if (owed.signum() > 0) {
          advances.computeIfAbsent(a.getEmployeeId(), k -> new ArrayList<>()).add(
              new PayrollInput.AdvanceBalance(a.getId(), a.getDate(), a.getMonthlyDeduction(),
                  owed));
        }
      }
      // 9. the run's lines as they are: manual items and overrides survive recalculation
      if (runId != null) {
        Criteria c = Criteria.where("shopId").is(shopId).and("runId").is(runId);
        if (onlyEmployees != null) {
          c = c.and("employeeId").in(ids);
        }
        Query lines = Query.query(c);
        lines.fields().include("employeeId", "manualItems", "advanceOverride", "frozenAt",
            "paymentStatus", "updatedAt");
        lines.maxTimeMsec(MAX_TIME_MS).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
        for (PayrollLine l : mongoTemplate.find(lines, PayrollLine.class)) {
          existing.put(l.getEmployeeId(), l);
        }
      }
    }

    List<PayrollInput.EmployeeInput> inputs = new ArrayList<>(employees.size());
    for (Employee e : employees) {
      PayrollLine old = existing.get(e.getId());
      inputs.add(new PayrollInput.EmployeeInput(e,
          marks.getOrDefault(e.getId(), Map.of()),
          leave.getOrDefault(e.getId(), Map.of()),
          old != null && old.getManualItems() != null ? old.getManualItems() : List.of(),
          old != null ? old.getAdvanceOverride() : null,
          advances.getOrDefault(e.getId(), List.of())));
    }
    return new Snapshot(new PayrollInput(month, settings, holidays, paidTypes, inputs, today),
        existing);
  }

  /** Employed at some point in the month; pay fields only when {@code withPay}. */
  private List<Employee> employees(String shopId, String from, String to, Set<String> only,
      boolean withPay) {
    List<Criteria> and = new ArrayList<>();
    and.add(Criteria.where("shopId").is(shopId));
    if (only != null) {
      and.add(Criteria.where("_id").in(only));
    }
    and.add(new Criteria().orOperator(Criteria.where("joinDate").is(null),
        Criteria.where("joinDate").lte(to)));
    and.add(new Criteria().orOperator(Criteria.where("exitDate").is(null),
        Criteria.where("exitDate").gte(from)));
    Query query = Query.query(new Criteria().andOperator(and.toArray(new Criteria[0])))
        .with(Sort.by(Sort.Direction.ASC, "name"));
    query.fields().include("code", "name", "designation", "joinDate", "exitDate", "workStart",
        "workEnd", "status");
    if (withPay) {
      // amounts only; bank, IFSC and UPI stay in the database
      query.fields().include("salaryRevisions");
    }
    query.maxTimeMsec(MAX_TIME_MS).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, Employee.class);
  }

  private List<AttendanceDay> days(String shopId, Collection<String> employeeIds, String from,
      String to) {
    Criteria c = Criteria.where("shopId").is(shopId);
    if (employeeIds != null) {
      c = c.and("employeeId").in(employeeIds);
    }
    Query query = Query.query(c.and("date").gte(from).lte(to));
    query.fields().include("employeeId", "date", "status", "inTime", "outTime", "workedMinutes",
        "autoHalfDay");
    query.maxTimeMsec(MAX_TIME_MS).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, AttendanceDay.class);
  }

  private List<Advance> activeAdvances(String shopId, Collection<String> employeeIds,
      String monthEnd) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
        .in(employeeIds).and("status").is(AdvanceStatus.ACTIVE).and("date").lte(monthEnd));
    query.fields().include("employeeId", "date", "amount", "monthlyDeduction");
    query.maxTimeMsec(MAX_TIME_MS).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, Advance.class);
  }
}
