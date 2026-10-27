package com.inventory.hr.service;

import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.LeaveYearStartMonth;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.LeaveYearUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Leave balances (R6.3, R6.4, D3): nothing is stored. Balance = prorated quota − approved days of
 * the type in the leave year, the latter summed by one aggregation per shop and year. Approving
 * then cancelling or rejecting therefore changes nothing (Property 3).
 */
@Service
public class LeaveBalanceService {

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrSettingsService settingsService;

  @Autowired private LeaveTypeService leaveTypeService;

  @Autowired private HrQueryRunner queryRunner;

  /** Quota, used and available of one type for one employee and year. */
  public record Balance(BigDecimal quota, BigDecimal used, BigDecimal available) {}

  /** {@code GET /leave-balances?year=}: every employee employed in the year (VIEW_ATTENDANCE). */
  public List<LeaveBalanceRow> list(String userId, String shopId, String year) {
    return list(userId, shopId, year, null);
  }

  /**
   * {@code GET /leave-balances?year=&employeeId=} (VIEW_ATTENDANCE): with {@code employeeId} only
   * that employee's row (empty when not employed in the year or not of this shop), so the
   * employee page does not load the whole shop (frontend gap b).
   */
  public List<LeaveBalanceRow> list(String userId, String shopId, String year,
      String employeeId) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    return queryRunner.read("leave_balances",
        () -> rows(shopId, year, StringUtils.hasText(employeeId) ? employeeId : null));
  }

  /** Self-service (task 10.1): the caller's own row only, found by {@code SelfServiceResolver}. */
  public List<LeaveBalanceRow> listForSelf(String shopId, Employee self, String year) {
    return queryRunner.read("leave_balances", () -> rows(shopId, year, self.getId()));
  }

  /** Rows without a permission check: callers check first (list, export, self-service). */
  List<LeaveBalanceRow> rows(String shopId, String year, String employeeId) {
    HrSettings settings = settingsService.effectiveSettings(shopId);
    LeaveYearStartMonth startMonth = settings.getLeaveYearStartMonth();
    String leaveYear = year != null ? year
        : LeaveYearUtils.leaveYearOf(HrDateUtils.today(), startMonth);
    LocalDate start = requireYear(leaveYear, startMonth);
    LocalDate end = LeaveYearUtils.end(start);
    List<LeaveType> types = leaveTypeService.ensureDefaults(shopId).stream()
        .filter(t -> !Boolean.FALSE.equals(t.getActive())).toList();
    List<Employee> employees = employeesInYear(shopId, start, end, employeeId);
    Map<String, BigDecimal> used = usedByEmployeeAndType(shopId, start, end, employeeId, null);

    List<LeaveBalanceRow> rows = new ArrayList<>(employees.size());
    for (Employee e : employees) {
      LeaveBalanceRow row = new LeaveBalanceRow();
      row.setEmployeeId(e.getId());
      row.setCode(e.getCode());
      row.setName(e.getName());
      row.setLeaveYear(leaveYear);
      for (LeaveType t : types) {
        Balance b = balance(t, e, start, end,
            used.getOrDefault(usedKey(e.getId(), t.getId()), BigDecimal.ZERO));
        row.getBalances().add(new LeaveBalanceRow.Balance(t.getId(), t.getName(),
            Boolean.TRUE.equals(t.getPaid()), b.quota(), b.used(), b.available()));
      }
      rows.add(row);
    }
    return rows;
  }

  /** One employee and type: a single aggregation (leave create, approve and preview). */
  public Balance balanceFor(String shopId, Employee employee, LeaveType type, String leaveYear,
      LeaveYearStartMonth startMonth) {
    LocalDate start = requireYear(leaveYear, startMonth);
    LocalDate end = LeaveYearUtils.end(start);
    BigDecimal used = usedByEmployeeAndType(shopId, start, end, employee.getId(), type.getId())
        .getOrDefault(usedKey(employee.getId(), type.getId()), BigDecimal.ZERO);
    return balance(type, employee, start, end, used);
  }

  static Balance balance(LeaveType type, Employee employee, LocalDate start, LocalDate end,
      BigDecimal used) {
    BigDecimal quota = type.getYearlyQuotaDays() == null ? null
        : proratedQuota(type.getYearlyQuotaDays(),
            HrDateUtils.effectiveJoinDateText(employee), start, end);
    return new Balance(quota, used, quota == null ? null : quota.subtract(used));
  }

  /**
   * R6.4: in the leave year the employee joined in, the quota shrinks to the share of the year
   * left from the join date on (both ends counted), rounded down to a half day. A join before
   * the year (or none) keeps the full quota; a join after it gives 0.
   */
  public static BigDecimal proratedQuota(BigDecimal quota, String joinDate, LocalDate start,
      LocalDate end) {
    LocalDate join = HrDateUtils.parseDate(joinDate);
    if (join == null || !join.isAfter(start)) {
      return quota;
    }
    if (join.isAfter(end)) {
      return BigDecimal.ZERO;
    }
    long yearDays = ChronoUnit.DAYS.between(start, end) + 1;
    long left = ChronoUnit.DAYS.between(join, end) + 1;
    BigDecimal share = quota.multiply(BigDecimal.valueOf(left))
        .divide(BigDecimal.valueOf(yearDays), 6, RoundingMode.DOWN);
    return roundDownToHalf(share);
  }

  /** Down to a multiple of 0.5: 4.9 → 4.5, 4.5 → 4.5. */
  public static BigDecimal roundDownToHalf(BigDecimal value) {
    return value.multiply(BigDecimal.valueOf(2)).setScale(0, RoundingMode.FLOOR)
        .divide(BigDecimal.valueOf(2)).setScale(1, RoundingMode.UNNECESSARY);
  }

  /** Σ days of the APPROVED requests (the aggregation's rule, for pure tests). */
  public static BigDecimal usedDays(Collection<LeaveRequest> requests, String leaveTypeId) {
    BigDecimal sum = BigDecimal.ZERO;
    for (LeaveRequest r : requests) {
      if (r.getStatus() == LeaveRequestStatus.APPROVED && leaveTypeId.equals(r.getLeaveTypeId())
          && r.getDays() != null) {
        sum = sum.add(r.getDays());
      }
    }
    return sum;
  }

  /**
   * Approved days per employee and type with {@code fromDate} in the year: one {@code $group}.
   * Requests never cross a leave-year boundary (LeaveService rejects that), so the start date
   * places each one; matching on {@code fromDate} rather than the stored {@code leaveYear} also
   * keeps balances right after the leave-year start month is changed (K6), and lets the {@code
   * (shopId, status, fromDate)} index bound the scan.
   */
  Map<String, BigDecimal> usedByEmployeeAndType(String shopId, LocalDate start, LocalDate end,
      String employeeId, String leaveTypeId) {
    Criteria c = Criteria.where("shopId").is(shopId).and("status")
        .is(LeaveRequestStatus.APPROVED).and("fromDate").gte(HrDateUtils.format(start))
        .lte(HrDateUtils.format(end));
    if (employeeId != null) {
      c = c.and("employeeId").is(employeeId);
    }
    if (leaveTypeId != null) {
      c = c.and("leaveTypeId").is(leaveTypeId);
    }
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(c),
        Aggregation.group("employeeId", "leaveTypeId").sum("days").as("used"))
        .withOptions(AggregationOptions.builder()
            .maxTime(Duration.ofMillis(HrQueryRunner.SCREEN_MAX_TIME_MS)).build());
    Map<String, BigDecimal> used = new HashMap<>();
    for (Document d : mongoTemplate.aggregate(aggregation, LeaveRequest.class, Document.class)
        .getMappedResults()) {
      Document id = d.get("_id", Document.class);
      used.put(usedKey(id.getString("employeeId"), id.getString("leaveTypeId")),
          toBigDecimal(d.get("used")));
    }
    return used;
  }

  private List<Employee> employeesInYear(String shopId, LocalDate start, LocalDate end,
      String employeeId) {
    Criteria shop = employeeId == null ? Criteria.where("shopId").is(shopId)
        : Criteria.where("shopId").is(shopId).and("_id").is(employeeId);
    Query query = Query.query(new Criteria().andOperator(
        shop,
        new Criteria().orOperator(Criteria.where("joinDate").is(null),
            Criteria.where("joinDate").lte(HrDateUtils.format(end))),
        new Criteria().orOperator(Criteria.where("exitDate").is(null),
            Criteria.where("exitDate").gte(HrDateUtils.format(start)))))
        .with(Sort.by(Sort.Direction.ASC, "name"));
    query.fields().include("code", "name", "joinDate", "exitDate", "createdAt");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.find(query, Employee.class);
  }

  static LocalDate requireYear(String leaveYear, LeaveYearStartMonth startMonth) {
    LocalDate start = LeaveYearUtils.start(leaveYear, startMonth);
    if (start == null) {
      throw new ValidationException(startMonth == LeaveYearStartMonth.APRIL
          ? "year must be a leave year like 2025-26" : "year must be a leave year like 2025");
    }
    return start;
  }

  private static String usedKey(String employeeId, String leaveTypeId) {
    return employeeId + "|" + leaveTypeId;
  }

  private static BigDecimal toBigDecimal(Object value) {
    if (value instanceof Decimal128 d) {
      return d.bigDecimalValue();
    }
    if (value instanceof BigDecimal b) {
      return b;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return BigDecimal.ZERO;
  }

  /** Only a paid type with a yearly quota is checked; unpaid leave has no limit (R6.13). */
  static boolean needsBalanceCheck(LeaveType type) {
    return Boolean.TRUE.equals(type.getPaid()) && type.getYearlyQuotaDays() != null;
  }
}
