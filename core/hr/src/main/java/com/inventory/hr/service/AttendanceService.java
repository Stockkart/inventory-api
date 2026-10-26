package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrAuditEvent;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.AttendanceHistoryResponse;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.AuditEventResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.rest.dto.response.MarkUnmarkedPresentResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.hr.validation.LeaveRequestValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Attendance (R5): daily and month registers, bulk save, "mark all unmarked as Present" and the
 * change history. Only marked days are stored (D2); everything else comes from {@link
 * DayStatusResolver}.
 *
 * <p>Round trips (design "Endpoint budgets"): every collection is read at most once per request,
 * with {@code $in} or a date range and a projection, never with a {@code findById} loop. Daily
 * register 6 HR queries, save 7, month 6, mark-unmarked 8. Screen reads carry a 3 s {@code
 * maxTimeMS}, month-wide reads 10 s; a read over its limit is 503 {@code HR_QUERY_TOO_SLOW}.
 */
@Slf4j
@Service
public class AttendanceService {

  static final String ENTITY = "ATTENDANCE_DAY";
  /** The 90-day cap on a leave request gives the overlay query its lower bound (O9). */
  static final int LEAVE_MAX_DAYS = LeaveRequestValidator.MAX_DAYS;
  static final int HISTORY_LIMIT = 50;
  static final int DUPLICATE_KEY = 11000;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrSettingsService settingsService;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private HrAuditService auditService;

  @Autowired private AttendanceDayValidator validator;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  @Autowired private HrUserNames userNames;

  // ---- daily register ------------------------------------------------------------------------

  /** Everyone employed on {@code asOf} (default today), resolved (R5.2–R5.4). */
  public DailyRegisterResponse daily(String userId, String shopId, String asOf) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    LocalDate date = asOf != null ? validator.requireDate("asOf", asOf) : HrDateUtils.today();
    String d = HrDateUtils.format(date);
    return queryRunner.read("attendance_daily", () -> {
      HrSettings settings = settingsService.effectiveSettings(shopId);
      List<Employee> employees =
          employeesEmployed(shopId, d, d, null, HrQueryRunner.SCREEN_MAX_TIME_MS);
      Map<String, AttendanceDay> marks = byEmployeeDate(
          days(shopId, null, d, d, HrQueryRunner.SCREEN_MAX_TIME_MS));
      Map<String, LeaveRequest> leave = leaveByEmployeeDate(
          approvedLeave(shopId, null, d, d, HrQueryRunner.SCREEN_MAX_TIME_MS), d, d);
      Set<String> holidays = holidayDates(shopId, d, d, HrQueryRunner.SCREEN_MAX_TIME_MS);
      boolean locked = !periodLockService.lockedPeriods(shopId,
          List.of(HrDateUtils.periodOf(d))).isEmpty();
      List<AttendanceRowResponse> rows = new ArrayList<>(employees.size());
      for (Employee e : employees) {
        rows.add(row(e, date, marks.get(key(e.getId(), d)), leave.get(key(e.getId(), d)),
            holidays.contains(d), settings, locked));
      }
      return new DailyRegisterResponse(d, locked, rows);
    });
  }

  // ---- save ----------------------------------------------------------------------------------

  /**
   * Saves up to 500 marks in one {@code bulkWrite}, upserting by {@code (shopId, employeeId,
   * date)}. Rejects the whole call when a row's month is locked (409 {@code HR_PERIOD_LOCKED}),
   * a row falls on an approved full-day leave day (409 {@code HR_DAY_HAS_LEAVE}) or outside the
   * employee's employment (422 {@code HR_EMPLOYEE_NOT_EMPLOYED}). Unchanged rows are not
   * written. Audit (Decision 7): one {@code ATTENDANCE_BULK_SET} for the first marks of the call,
   * one {@code ATTENDANCE_SET} with before/after per changed or cleared existing mark.
   */
  public List<AttendanceRowResponse> saveDays(String userId, String shopId,
      SaveAttendanceDaysRequest request) {
    guard.require(userId, shopId, HrPermission.MARK_ATTENDANCE);
    validator.validateSave(request, HrDateUtils.today());
    List<SaveAttendanceDaysRequest.Row> rows = request.getRows();
    Set<String> employeeIds = new LinkedHashSet<>();
    TreeSet<String> dates = new TreeSet<>();
    Set<String> periods = new TreeSet<>();
    for (SaveAttendanceDaysRequest.Row r : rows) {
      employeeIds.add(r.getEmployeeId());
      dates.add(r.getDate());
      periods.add(HrDateUtils.periodOf(r.getDate()));
    }
    String from = dates.first();
    String to = dates.last();

    return queryRunner.read("attendance_save", () -> {
      // 1. employees of this shop only: an id from another shop is a 404, never a write
      Map<String, Employee> employees = employeesById(shopId, employeeIds);
      for (SaveAttendanceDaysRequest.Row r : rows) {
        Employee e = employees.get(r.getEmployeeId());
        if (e == null) {
          throw new ResourceNotFoundException("Employee", "id", r.getEmployeeId());
        }
        if (!DayStatusResolver.isEmployed(HrDateUtils.parseDate(r.getDate()), e)) {
          throw notEmployed(e, r.getDate());
        }
      }
      // 2. lock: one read for every month the rows touch
      periodLockService.assertOpen(shopId, periods);
      // 3. approved leave of these employees in the range
      Map<String, LeaveRequest> leave = leaveByEmployeeDate(
          approvedLeave(shopId, employeeIds, from, to, HrQueryRunner.SCREEN_MAX_TIME_MS), from,
          to);
      // 4. settings (hours, grace, half-day rule, weekly off)
      HrSettings settings = settingsService.effectiveSettings(shopId);
      HolidayCache holidays = new HolidayCache(shopId, from, to);
      for (SaveAttendanceDaysRequest.Row r : rows) {
        LeaveRequest l = leave.get(key(r.getEmployeeId(), r.getDate()));
        // a full-day leave owns its working days (D4); half-day leave days take a mark for the
        // other half, and a leave range's week-offs and holidays are not leave days at all
        if (l != null && !Boolean.TRUE.equals(l.getHalfDay())
            && isLeaveWorkingDay(r.getDate(), settings, holidays)) {
          throw new BaseException(ErrorCode.HR_DAY_HAS_LEAVE,
              "Change or cancel the leave first ("
                  + employees.get(r.getEmployeeId()).getCode() + " on " + r.getDate() + ")");
        }
      }
      // 5. existing marks, for the audit "before" and to skip unchanged rows
      Map<String, AttendanceDay> existing = byEmployeeDate(
          days(shopId, employeeIds, from, to, HrQueryRunner.SCREEN_MAX_TIME_MS));

      Instant now = Instant.now();
      BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
          AttendanceDay.class);
      int writes = 0;
      List<AuditEntry> changes = new ArrayList<>();
      TreeSet<String> firstMarkDates = new TreeSet<>();
      int firstMarks = 0;
      Map<String, AttendanceDay> after = new HashMap<>();
      for (SaveAttendanceDaysRequest.Row r : rows) {
        String k = key(r.getEmployeeId(), r.getDate());
        AttendanceDay old = existing.get(k);
        AttendanceStatus status = validator.parseStatus(r.getStatus());
        if (status == null) {
          // UNMARKED: clear a stored mark; nothing to do when none is stored
          if (old != null) {
            bulk.remove(dayKey(shopId, r.getEmployeeId(), r.getDate()));
            writes++;
            changes.add(changeEntry(shopId, userId, r, snapshot(old), null));
          }
          after.put(k, null);
          continue;
        }
        AttendanceDay day = newDay(shopId, userId, r, status, settings, now);
        after.put(k, day);
        if (old != null && sameMark(old, day)) {
          after.put(k, old);
          continue;
        }
        bulk.upsert(dayKey(shopId, r.getEmployeeId(), r.getDate()), markUpdate(day));
        writes++;
        if (old == null) {
          firstMarks++;
          firstMarkDates.add(r.getDate());
        } else {
          changes.add(changeEntry(shopId, userId, r, snapshot(old), snapshot(day)));
        }
      }
      // 6. one bulkWrite for every upsert and clear
      if (writes > 0) {
        executeSave(bulk);
        metrics.increment(HrMetricsConstants.ATTENDANCE_WRITES_TOTAL, writes,
            "module", HrMetricsConstants.MODULE, "operation", "save");
      }
      // 7. one insertMany: the summary of first marks plus one row per changed existing mark
      if (firstMarks > 0) {
        changes.add(0, bulkSummary(shopId, userId, firstMarks, firstMarkDates,
            AttendanceSource.MANUAL));
      }
      auditService.recordAll(changes);

      List<AttendanceRowResponse> response = new ArrayList<>(rows.size());
      for (SaveAttendanceDaysRequest.Row r : rows) {
        String k = key(r.getEmployeeId(), r.getDate());
        AttendanceDay day = after.get(k);
        // a cleared day, or a mark inside a leave range, shows what it derives to, which depends
        // on whether the date is a holiday (a leave never covers a holiday)
        boolean holiday = (day == null || leave.get(k) != null) && holidays.contains(r.getDate());
        response.add(row(employees.get(r.getEmployeeId()), HrDateUtils.parseDate(r.getDate()),
            day, leave.get(k), holiday, settings, false));
      }
      return response;
    });
  }

  // ---- mark all unmarked as present ----------------------------------------------------------

  /**
   * Marks every unmarked working day of the given dates Present (R5.10, R8.12). Uses an upsert
   * with only {@code $setOnInsert}: if somebody marks the same day between our read and this
   * write, the upsert matches their row and changes nothing, so a mark is never overwritten.
   */
  public MarkUnmarkedPresentResponse markUnmarkedPresent(String userId, String shopId,
      MarkUnmarkedPresentRequest request) {
    guard.require(userId, shopId, HrPermission.MARK_ATTENDANCE);
    List<LocalDate> dates = validator.validateMarkUnmarked(request, HrDateUtils.today());
    String from = HrDateUtils.format(dates.get(0));
    String to = HrDateUtils.format(dates.get(dates.size() - 1));
    String period = HrDateUtils.periodOf(from);
    Set<String> ids = request.getEmployeeIds() == null || request.getEmployeeIds().isEmpty()
        ? null : new LinkedHashSet<>(request.getEmployeeIds());

    return queryRunner.read("attendance_mark_unmarked", () -> {
      HrSettings settings = settingsService.effectiveSettings(shopId);
      Set<String> holidays = holidayDates(shopId, from, to, HrQueryRunner.MONTH_MAX_TIME_MS);
      List<Employee> employees =
          employeesEmployed(shopId, from, to, ids, HrQueryRunner.MONTH_MAX_TIME_MS);
      periodLockService.assertOpen(shopId, List.of(period));
      Collection<String> employeeIds = employees.stream().map(Employee::getId).toList();
      if (employeeIds.isEmpty()) {
        return new MarkUnmarkedPresentResponse(0);
      }
      Map<String, LeaveRequest> leave = leaveByEmployeeDate(
          approvedLeave(shopId, employeeIds, from, to, HrQueryRunner.MONTH_MAX_TIME_MS), from,
          to);
      Set<String> marked = new HashSet<>(byEmployeeDate(
          daysKeysOnly(shopId, employeeIds, from, to)).keySet());

      Instant now = Instant.now();
      BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
          AttendanceDay.class);
      int candidates = 0;
      for (Employee e : employees) {
        for (LocalDate date : dates) {
          String d = HrDateUtils.format(date);
          String k = key(e.getId(), d);
          if (marked.contains(k) || !DayStatusResolver.isEmployed(date, e)
              || holidays.contains(d) || DayStatusResolver.isWeekOff(date, settings)) {
            continue;
          }
          LeaveRequest l = leave.get(k);
          if (l != null && !Boolean.TRUE.equals(l.getHalfDay())) {
            // full-day leave: the day is LEAVE, not unmarked
            continue;
          }
          bulk.upsert(dayKey(shopId, e.getId(), d), presentOnInsert(shopId, userId, e.getId(),
              d, now));
          candidates++;
        }
      }
      if (candidates == 0) {
        return new MarkUnmarkedPresentResponse(0);
      }
      int inserted = executeInsertOnly(bulk);
      if (inserted > 0) {
        metrics.increment(HrMetricsConstants.ATTENDANCE_WRITES_TOTAL, inserted,
            "module", HrMetricsConstants.MODULE, "operation", "mark_unmarked_present");
        auditService.recordAll(List.of(bulkSummary(shopId, userId, inserted,
            new TreeSet<>(List.of(from, to)), AttendanceSource.BULK_PRESENT)));
      }
      return new MarkUnmarkedPresentResponse(inserted);
    });
  }

  // ---- month register ------------------------------------------------------------------------

  /** Employees × days of a month with totals (R5.11, R5.12); one employee when given. */
  public MonthRegisterResponse month(String userId, String shopId, String period,
      String employeeId) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    Set<String> only = StringUtils.hasText(employeeId) ? Set.of(employeeId) : null;
    return monthRegister(shopId, period, only);
  }

  /**
   * Self-service month (task 10.1, {@code GET /me/attendance}): one row, the caller's own
   * employee as found by {@code SelfServiceResolver}; no HR permission needed.
   */
  public MonthRegisterResponse monthForSelf(String shopId, Employee self, String period) {
    return monthRegister(shopId, period, Set.of(self.getId()));
  }

  /** The register without a permission check: callers check first (reports, self-service). */
  MonthRegisterResponse monthRegister(String shopId, String period, Set<String> only) {
    YearMonth month = validator.requirePeriod(period);
    String from = HrDateUtils.format(month.atDay(1));
    String to = HrDateUtils.format(month.atEndOfMonth());
    return queryRunner.read("attendance_month", () -> {
      HrSettings settings = settingsService.effectiveSettings(shopId);
      Set<String> holidays = holidayDates(shopId, from, to, HrQueryRunner.MONTH_MAX_TIME_MS);
      List<Employee> employees =
          employeesEmployed(shopId, from, to, only, HrQueryRunner.MONTH_MAX_TIME_MS);
      Map<String, AttendanceDay> marks = byEmployeeDate(
          days(shopId, only, from, to, HrQueryRunner.MONTH_MAX_TIME_MS));
      Map<String, LeaveRequest> leave = leaveByEmployeeDate(
          approvedLeave(shopId, only, from, to, HrQueryRunner.MONTH_MAX_TIME_MS), from, to);
      boolean locked = !periodLockService.lockedPeriods(shopId, List.of(period)).isEmpty();

      MonthRegisterResponse response = new MonthRegisterResponse();
      response.setPeriod(period);
      response.setLocked(locked);
      // days after today (Asia/Kolkata) are not Unmarked yet: flagged and left out of the count
      LocalDate today = HrDateUtils.today();
      List<LocalDate> days = new ArrayList<>();
      for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
        days.add(d);
        response.getDates().add(HrDateUtils.format(d));
      }
      for (Employee e : employees) {
        MonthRegisterResponse.Row row = new MonthRegisterResponse.Row();
        row.setEmployeeId(e.getId());
        row.setCode(e.getCode());
        row.setName(e.getName());
        MonthRegisterResponse.Totals totals = new MonthRegisterResponse.Totals();
        for (LocalDate date : days) {
          String d = HrDateUtils.format(date);
          String k = key(e.getId(), d);
          DayStatusResolver.Resolution r = DayStatusResolver.resolve(date, e, marks.get(k),
              leave.get(k), holidays.contains(d), settings);
          boolean future = date.isAfter(today);
          row.getCells().add(new MonthRegisterResponse.Cell(r.status(),
              r.late() ? Boolean.TRUE : null, r.halfDayLeave() ? Boolean.TRUE : null,
              r.otherHalf(), future ? Boolean.TRUE : null));
          addToTotals(totals, r, future);
        }
        row.setTotals(totals);
        response.getRows().add(row);
      }
      return response;
    });
  }

  // ---- history -------------------------------------------------------------------------------

  /** Who marked the day and every later change, newest first (R5.15). */
  public AttendanceHistoryResponse history(String userId, String shopId, String employeeId,
      String date) {
    guard.require(userId, shopId, HrPermission.MARK_ATTENDANCE);
    if (!StringUtils.hasText(employeeId)) {
      throw new ValidationException("employeeId is required");
    }
    validator.requireDate("date", date);
    return queryRunner.read("attendance_history", () -> {
      Query employee = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(employeeId));
      employee.fields().include("_id");
      employee.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      if (!mongoTemplate.exists(employee, Employee.class)) {
        throw new ResourceNotFoundException("Employee", "id", employeeId);
      }
      AttendanceDay day = mongoTemplate.findOne(dayKey(shopId, employeeId, date)
          .maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS), AttendanceDay.class);
      Query events = Query.query(Criteria.where("shopId").is(shopId).and("entityType").is(ENTITY)
          .and("employeeId").is(employeeId).and("date").is(date))
          .with(Sort.by(Sort.Direction.DESC, "at")).limit(HISTORY_LIMIT);
      events.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<HrAuditEvent> found = mongoTemplate.find(events, HrAuditEvent.class);
      // one batched name lookup for the first marker and every actor (frontend gap a)
      List<String> actors = new ArrayList<>();
      found.forEach(ev -> actors.add(ev.getActorUserId()));
      if (day != null) {
        actors.add(day.getMarkedByUserId());
      }
      Map<String, String> names = userNames.namesOf(actors);
      List<AuditEventResponse> rows = found.stream()
          .map(ev -> new AuditEventResponse(ev.getAction(), ev.getActorUserId(),
              names.get(ev.getActorUserId()), ev.getAt(), ev.getBefore(), ev.getAfter(),
              ev.getReason()))
          .toList();
      AttendanceHistoryResponse response = new AttendanceHistoryResponse(employeeId, date,
          day != null ? day.getStatus() : null, day != null ? day.getSource() : null,
          day != null ? day.getMarkedAt() : null, day != null ? day.getMarkedByUserId() : null,
          day != null ? names.get(day.getMarkedByUserId()) : null, rows);
      return response;
    });
  }

  // ---- reads (each one round trip) -----------------------------------------------------------

  /**
   * Employees employed at some point in {@code [from, to]}: joined on or before {@code to} (or no
   * join date) and not exited before {@code from}. Only the fields the resolver and the register
   * use; pay and payment fields stay in the database.
   */
  List<Employee> employeesEmployed(String shopId, String from, String to, Set<String> ids,
      long maxTimeMs) {
    List<Criteria> and = new ArrayList<>();
    and.add(Criteria.where("shopId").is(shopId));
    if (ids != null) {
      and.add(Criteria.where("_id").in(ids));
    }
    and.add(new Criteria().orOperator(Criteria.where("joinDate").is(null),
        Criteria.where("joinDate").lte(to)));
    and.add(new Criteria().orOperator(Criteria.where("exitDate").is(null),
        Criteria.where("exitDate").gte(from)));
    Query query = Query.query(new Criteria().andOperator(and.toArray(new Criteria[0])))
        .with(Sort.by(Sort.Direction.ASC, "name"));
    employeeFields(query);
    query.maxTimeMsec(maxTimeMs).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, Employee.class);
  }

  private Map<String, Employee> employeesById(String shopId, Collection<String> ids) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").in(ids));
    employeeFields(query);
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS)
        .cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    Map<String, Employee> byId = new HashMap<>();
    for (Employee e : mongoTemplate.find(query, Employee.class)) {
      byId.put(e.getId(), e);
    }
    return byId;
  }

  private static void employeeFields(Query query) {
    query.fields().include("code", "name", "designation", "joinDate", "exitDate", "createdAt",
        "workStart", "workEnd", "status");
  }

  private List<AttendanceDay> days(String shopId, Collection<String> employeeIds, String from,
      String to, long maxTimeMs) {
    Query query = Query.query(dayRange(shopId, employeeIds, from, to));
    query.fields().include("employeeId", "date", "status", "inTime", "outTime", "workedMinutes",
        "autoHalfDay", "source", "note", "markedAt", "markedByUserId");
    query.maxTimeMsec(maxTimeMs).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, AttendanceDay.class);
  }

  private List<AttendanceDay> daysKeysOnly(String shopId, Collection<String> employeeIds,
      String from, String to) {
    Query query = Query.query(dayRange(shopId, employeeIds, from, to));
    query.fields().include("employeeId", "date");
    query.maxTimeMsec(HrQueryRunner.MONTH_MAX_TIME_MS)
        .cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, AttendanceDay.class);
  }

  private static Criteria dayRange(String shopId, Collection<String> employeeIds, String from,
      String to) {
    Criteria c = Criteria.where("shopId").is(shopId);
    if (employeeIds != null) {
      c = c.and("employeeId").in(employeeIds);
    }
    return c.and("date").gte(from).lte(to);
  }

  /**
   * Approved leave overlapping {@code [from, to]}. A request is at most 90 days long, so its
   * {@code fromDate} lies in {@code [from − 89, to]}: that bound lets the {@code (shopId, status,
   * fromDate)} index do the work instead of scanning the shop's whole leave history.
   */
  List<LeaveRequest> approvedLeave(String shopId, Collection<String> employeeIds, String from,
      String to, long maxTimeMs) {
    String lowest = HrDateUtils.format(HrDateUtils.parseDate(from).minusDays(LEAVE_MAX_DAYS - 1));
    Criteria c = Criteria.where("shopId").is(shopId).and("status")
        .is(LeaveRequestStatus.APPROVED).and("fromDate").gte(lowest).lte(to)
        .and("toDate").gte(from);
    if (employeeIds != null) {
      c = c.and("employeeId").in(employeeIds);
    }
    Query query = Query.query(c);
    query.fields().include("employeeId", "leaveTypeId", "fromDate", "toDate", "halfDay");
    query.maxTimeMsec(maxTimeMs).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    return mongoTemplate.find(query, LeaveRequest.class);
  }

  Set<String> holidayDates(String shopId, String from, String to, long maxTimeMs) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("date").gte(from).lte(to));
    query.fields().include("date");
    query.maxTimeMsec(maxTimeMs).cursorBatchSize(HrQueryRunner.BULK_BATCH_SIZE);
    Set<String> dates = new HashSet<>();
    for (Holiday h : mongoTemplate.find(query, Holiday.class)) {
      dates.add(h.getDate());
    }
    return dates;
  }

  /**
   * Holidays read only when a save needs them (a full-day leave row, or a cleared row), so the
   * usual save stays at its 7 queries.
   */
  private final class HolidayCache {
    private final String shopId;
    private final String from;
    private final String to;
    private Set<String> dates;

    HolidayCache(String shopId, String from, String to) {
      this.shopId = shopId;
      this.from = from;
      this.to = to;
    }

    boolean contains(String date) {
      if (dates == null) {
        dates = holidayDates(shopId, from, to, HrQueryRunner.SCREEN_MAX_TIME_MS);
      }
      return dates.contains(date);
    }
  }

  private static boolean isLeaveWorkingDay(String date, HrSettings settings,
      HolidayCache holidays) {
    // week-off first: it needs no read
    return !DayStatusResolver.isWeekOff(HrDateUtils.parseDate(date), settings)
        && !holidays.contains(date);
  }

  // ---- writes --------------------------------------------------------------------------------

  private static Query dayKey(String shopId, String employeeId, String date) {
    return Query.query(Criteria.where("shopId").is(shopId).and("employeeId").is(employeeId)
        .and("date").is(date));
  }

  private static AttendanceDay newDay(String shopId, String userId,
      SaveAttendanceDaysRequest.Row r, AttendanceStatus status, HrSettings settings,
      Instant now) {
    AttendanceDay day = new AttendanceDay();
    day.setShopId(shopId);
    day.setEmployeeId(r.getEmployeeId());
    day.setDate(r.getDate());
    day.setPeriod(HrDateUtils.periodOf(r.getDate()));
    day.setInTime(blankToNull(r.getInTime()));
    day.setOutTime(blankToNull(r.getOutTime()));
    day.setWorkedMinutes(DayStatusResolver.workedMinutes(day.getInTime(), day.getOutTime()));
    boolean auto = DayStatusResolver.isAutoHalfDay(status, day.getWorkedMinutes(), settings);
    // R2.5: a Present day with too few hours is stored as a half day, flagged as automatic
    day.setStatus(auto ? AttendanceStatus.HALF_DAY : status);
    day.setAutoHalfDay(auto ? Boolean.TRUE : null);
    day.setSource(AttendanceSource.MANUAL);
    day.setNote(blankToNull(r.getNote() != null ? r.getNote().trim() : null));
    day.setMarkedAt(now);
    day.setMarkedByUserId(userId);
    return day;
  }

  /** Full mark on update; the key fields only on insert. */
  private static Update markUpdate(AttendanceDay day) {
    return new Update()
        .set("status", day.getStatus())
        .set("inTime", day.getInTime())
        .set("outTime", day.getOutTime())
        .set("workedMinutes", day.getWorkedMinutes())
        .set("autoHalfDay", day.getAutoHalfDay())
        .set("source", day.getSource())
        .set("note", day.getNote())
        .set("period", day.getPeriod())
        .set("markedAt", day.getMarkedAt())
        .set("markedByUserId", day.getMarkedByUserId())
        .setOnInsert("shopId", day.getShopId())
        .setOnInsert("employeeId", day.getEmployeeId())
        .setOnInsert("date", day.getDate());
  }

  /** Every field under {@code $setOnInsert}: an existing day is matched and left untouched. */
  private static Update presentOnInsert(String shopId, String userId, String employeeId,
      String date, Instant now) {
    return new Update()
        .setOnInsert("shopId", shopId)
        .setOnInsert("employeeId", employeeId)
        .setOnInsert("date", date)
        .setOnInsert("period", HrDateUtils.periodOf(date))
        .setOnInsert("status", AttendanceStatus.PRESENT)
        .setOnInsert("source", AttendanceSource.BULK_PRESENT)
        .setOnInsert("markedAt", now)
        .setOnInsert("markedByUserId", userId);
  }

  /** A duplicate key here means a concurrent save inserted the same day first: ask to reload. */
  private static void executeSave(BulkOperations bulk) {
    try {
      bulk.execute();
    } catch (BulkOperationException e) {
      if (e.getErrors().stream().allMatch(err -> err.getCode() == DUPLICATE_KEY)) {
        throw new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
            ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
      }
      throw e;
    }
  }

  /**
   * Runs an insert-only bulk and returns how many days it inserted. Two concurrent calls can
   * both try to insert the same day; the unique index makes the loser fail with a duplicate key,
   * which here only means "somebody else marked it", so those errors are dropped.
   */
  private static int executeInsertOnly(BulkOperations bulk) {
    try {
      BulkWriteResult result = bulk.execute();
      return result.getUpserts().size();
    } catch (BulkOperationException e) {
      for (BulkWriteError error : e.getErrors()) {
        if (error.getCode() != DUPLICATE_KEY) {
          throw e;
        }
      }
      return e.getResult().getUpserts().size();
    }
  }

  private static boolean sameMark(AttendanceDay old, AttendanceDay now) {
    return old.getStatus() == now.getStatus()
        && Objects.equals(old.getInTime(), now.getInTime())
        && Objects.equals(old.getOutTime(), now.getOutTime())
        && Objects.equals(old.getNote(), now.getNote());
  }

  private static Map<String, Object> snapshot(AttendanceDay day) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", String.valueOf(day.getStatus()));
    m.put("inTime", day.getInTime());
    m.put("outTime", day.getOutTime());
    m.put("note", day.getNote());
    m.put("source", day.getSource() != null ? day.getSource().name() : null);
    return m;
  }

  private static AuditEntry changeEntry(String shopId, String userId,
      SaveAttendanceDaysRequest.Row r, Map<String, Object> before, Map<String, Object> after) {
    return new AuditEntry(shopId, userId, HrAuditAction.ATTENDANCE_SET, ENTITY, null,
        r.getEmployeeId(), r.getDate(), HrDateUtils.periodOf(r.getDate()), before, after, null);
  }

  /** One summary row for the first marks of a call: how many and which date range (R5.15). */
  private static AuditEntry bulkSummary(String shopId, String userId, int count,
      TreeSet<String> dates, AttendanceSource source) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("count", count);
    after.put("from", dates.first());
    after.put("to", dates.last());
    after.put("source", source.name());
    String period = HrDateUtils.periodOf(dates.first());
    return new AuditEntry(shopId, userId, HrAuditAction.ATTENDANCE_BULK_SET, ENTITY, null, null,
        null, period, null, after, null);
  }

  // ---- mapping -------------------------------------------------------------------------------

  private static AttendanceRowResponse row(Employee e, LocalDate date, AttendanceDay mark,
      LeaveRequest leave, boolean holiday, HrSettings settings, boolean locked) {
    DayStatusResolver.Resolution r =
        DayStatusResolver.resolve(date, e, mark, leave, holiday, settings);
    AttendanceRowResponse row = new AttendanceRowResponse();
    row.setEmployeeId(e.getId());
    row.setCode(e.getCode());
    row.setName(e.getName());
    row.setDesignation(e.getDesignation());
    row.setDate(HrDateUtils.format(date));
    row.setStatus(r.status());
    row.setSource(r.source());
    row.setHalfDayLeave(r.halfDayLeave());
    row.setOtherHalf(r.otherHalf());
    row.setLeaveRequestId(r.leaveRequestId());
    row.setLate(r.late());
    row.setWorkedMinutes(r.workedMinutes());
    row.setOvertimeMinutes(r.overtimeMinutes());
    row.setAutoHalfDay(r.autoHalfDay());
    // the stored mark is shown only when it is what the day shows (a full-day leave hides it)
    if (mark != null && (r.source() == DayStatusResolver.Source.MARK || r.halfDayLeave())) {
      row.setInTime(mark.getInTime());
      row.setOutTime(mark.getOutTime());
      row.setNote(mark.getNote());
      row.setMarkedAt(mark.getMarkedAt());
      row.setMarkedByUserId(mark.getMarkedByUserId());
    }
    row.setLocked(locked);
    return row;
  }

  /**
   * Adds one day to the totals. A {@code future} day (after today) that resolves to Unmarked, or
   * the unmarked other half of a future half-day leave, is not counted as Unmarked: the day has
   * not happened yet.
   */
  static void addToTotals(MonthRegisterResponse.Totals t, DayStatusResolver.Resolution r,
      boolean future) {
    BigDecimal half = LeaveDayCounter.HALF;
    switch (r.status()) {
      case PRESENT -> t.setPresent(t.getPresent().add(BigDecimal.ONE));
      case HALF_DAY -> t.setHalfDays(t.getHalfDays() + 1);
      case ABSENT -> t.setAbsent(t.getAbsent().add(BigDecimal.ONE));
      case WEEK_OFF -> t.setWeekOff(t.getWeekOff().add(BigDecimal.ONE));
      case HOLIDAY -> t.setHoliday(t.getHoliday().add(BigDecimal.ONE));
      case UNMARKED -> {
        if (!future) {
          t.setUnmarked(t.getUnmarked().add(BigDecimal.ONE));
        }
      }
      case LEAVE -> {
        t.setLeave(t.getLeave().add(r.leaveDays()));
        if (r.halfDayLeave() && r.otherHalf() != null) {
          switch (r.otherHalf()) {
            case PRESENT -> t.setPresent(t.getPresent().add(half));
            case ABSENT -> t.setAbsent(t.getAbsent().add(half));
            case WEEK_OFF -> t.setWeekOff(t.getWeekOff().add(half));
            case HOLIDAY -> t.setHoliday(t.getHoliday().add(half));
            default -> {
              if (!future) {
                t.setUnmarked(t.getUnmarked().add(half));
              }
            }
          }
        }
      }
      case NOT_EMPLOYED -> {
        // outside employment: not part of the totals
      }
    }
    if (r.late()) {
      t.setLateDays(t.getLateDays() + 1);
    }
    if (r.workedMinutes() != null) {
      t.setWorkedMinutes(t.getWorkedMinutes() + r.workedMinutes());
    }
    t.setOvertimeMinutes(t.getOvertimeMinutes() + r.overtimeMinutes());
  }

  // ---- helpers -------------------------------------------------------------------------------

  static String key(String employeeId, String date) {
    return employeeId + "|" + date;
  }

  private static Map<String, AttendanceDay> byEmployeeDate(List<AttendanceDay> days) {
    Map<String, AttendanceDay> map = new HashMap<>();
    for (AttendanceDay d : days) {
      map.put(key(d.getEmployeeId(), d.getDate()), d);
    }
    return map;
  }

  /**
   * Leave per employee and date within {@code [from, to]}. Approved requests of one employee
   * never overlap (checked on create), so a date has at most one.
   */
  static Map<String, LeaveRequest> leaveByEmployeeDate(List<LeaveRequest> requests, String from,
      String to) {
    Map<String, LeaveRequest> map = new HashMap<>();
    LocalDate lo = HrDateUtils.parseDate(from);
    LocalDate hi = HrDateUtils.parseDate(to);
    for (LeaveRequest l : requests) {
      LocalDate start = HrDateUtils.parseDate(l.getFromDate());
      LocalDate end = HrDateUtils.parseDate(l.getToDate());
      if (start == null || end == null) {
        continue;
      }
      for (LocalDate d = start.isBefore(lo) ? lo : start; !d.isAfter(end) && !d.isAfter(hi);
          d = d.plusDays(1)) {
        map.putIfAbsent(key(l.getEmployeeId(), HrDateUtils.format(d)), l);
      }
    }
    return map;
  }

  private static BaseException notEmployed(Employee e, String date) {
    return new BaseException(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED,
        e.getCode() + " was not employed on " + date);
  }

  private static String blankToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

}
