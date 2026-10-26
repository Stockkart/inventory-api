package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.LeaveType;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveOrigin;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.repository.LeaveRequestRepository;
import com.inventory.hr.domain.repository.LeaveTypeRepository;
import com.inventory.hr.rest.dto.request.RecordLeaveRequest;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.response.LeavePreviewResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestsPageResponse;
import com.inventory.hr.rest.dto.response.PageMeta;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.LeaveYearUtils;
import com.inventory.hr.validation.LeaveRequestValidator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Leave (R6.5–R6.13): self-service requests, leave recorded by staff (approved at once), preview,
 * approve, reject and cancel.
 *
 * <p>Rules: at most 90 days; half day only for one date; a request stays inside one leave year;
 * days = working days (week-offs and holidays skipped); no overlap with another PENDING or
 * APPROVED request of the same employee; a paid type may not go over its balance (the user is told
 * to choose an unpaid type, R6.13); approve, record and cancel of approved leave check the month
 * lock.
 *
 * <p>Approved leave is an overlay that wins over stored marks on read (D4). Approve and record
 * therefore change one request document, then delete the full-day marks of its working days as a
 * clean-up: if that delete is lost, the register still shows LEAVE, and running it again changes
 * nothing.
 */
@Slf4j
@Service
public class LeaveService {

  static final String ENTITY = "LEAVE_REQUEST";
  static final int DEFAULT_PAGE_SIZE = 50;
  static final int MAX_PAGE_SIZE = 100;
  static final List<LeaveRequestStatus> LIVE =
      List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVED);

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private LeaveRequestRepository leaveRequestRepository;

  @Autowired private LeaveTypeRepository leaveTypeRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private SelfServiceResolver selfServiceResolver;

  @Autowired private HrSettingsService settingsService;

  @Autowired private LeaveBalanceService balanceService;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private HrAuditService auditService;

  @Autowired private HrNotificationService notificationService;

  @Autowired private LeaveRequestValidator validator;

  @Autowired private HrQueryRunner queryRunner;

  /** What a request covers once settings and holidays are applied. */
  record Plan(LeaveType type, HrSettings settings, List<LocalDate> workingDates, BigDecimal days,
      String leaveYear, LocalDate from, LocalDate to, boolean halfDay) {}

  // ---- list and preview ----------------------------------------------------------------------

  /** {@code GET /leave-requests} (VIEW_ATTENDANCE), newest first. */
  public LeaveRequestsPageResponse list(String userId, String shopId, String status,
      String employeeId, Integer page, Integer size) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    int p = page != null ? page : 0;
    int s = size != null ? size : DEFAULT_PAGE_SIZE;
    if (p < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    if (s < 1 || s > MAX_PAGE_SIZE) {
      throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
    Criteria c = Criteria.where("shopId").is(shopId);
    if (StringUtils.hasText(status)) {
      c = c.and("status").is(parseStatus(status));
    }
    if (StringUtils.hasText(employeeId)) {
      c = c.and("employeeId").is(employeeId);
    }
    Criteria criteria = c;
    return queryRunner.read("leave_list", () -> {
      Query count = Query.query(criteria);
      count.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      long total = mongoTemplate.count(count, LeaveRequest.class);
      Query query = Query.query(criteria)
          .with(PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "fromDate")));
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<LeaveRequest> found = mongoTemplate.find(query, LeaveRequest.class);
      Map<String, Employee> names = employeeNames(shopId,
          found.stream().map(LeaveRequest::getEmployeeId).toList());
      // one batched read for the type names the leave page shows (frontend gap c)
      Map<String, String> typeNames = leaveTypeNames(shopId,
          found.stream().map(LeaveRequest::getLeaveTypeId).toList());
      List<LeaveRequestResponse> items = found.stream()
          .map(r -> toResponse(r, names.get(r.getEmployeeId()),
              typeNames.get(r.getLeaveTypeId()))).toList();
      int totalPages = (int) ((total + s - 1) / s);
      return new LeaveRequestsPageResponse(items, new PageMeta(p, s, total, totalPages));
    });
  }

  /**
   * Self-service list (task 10.1, {@code GET /me/leave}): the caller's own requests, newest first,
   * at most {@link #MAX_PAGE_SIZE}. The employee comes from {@code SelfServiceResolver}.
   */
  public List<LeaveRequestResponse> requestsOf(String shopId, Employee self) {
    return queryRunner.read("leave_list_own", () -> {
      Query query = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
          .is(self.getId())).with(Sort.by(Sort.Direction.DESC, "fromDate")).limit(MAX_PAGE_SIZE);
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<LeaveRequest> found = mongoTemplate.find(query, LeaveRequest.class);
      Map<String, String> typeNames = leaveTypeNames(shopId,
          found.stream().map(LeaveRequest::getLeaveTypeId).toList());
      return found.stream()
          .map(r -> toResponse(r, self, typeNames.get(r.getLeaveTypeId()))).toList();
    });
  }

  /** {@code GET /leave-requests/preview} (MARK_ATTENDANCE): days and balance before sending. */
  public LeavePreviewResponse preview(String userId, String shopId, String employeeId,
      String leaveTypeId, String from, String to, Boolean halfDay) {
    guard.require(userId, shopId, HrPermission.MARK_ATTENDANCE);
    validator.requireEmployeeId(employeeId);
    LeaveRequestValidator.Range range = validator.validate(leaveTypeId, from, to, halfDay, null);
    return queryRunner.read("leave_preview",
        () -> previewFor(shopId, requireEmployee(shopId, employeeId), leaveTypeId, range));
  }

  /** Self-service preview (endpoint in task 10.1). */
  public LeavePreviewResponse previewForSelf(String shopId, String userId, String leaveTypeId,
      String from, String to, Boolean halfDay) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    LeaveRequestValidator.Range range = validator.validate(leaveTypeId, from, to, halfDay, null);
    return queryRunner.read("leave_preview", () -> previewFor(shopId, self, leaveTypeId, range));
  }

  private LeavePreviewResponse previewFor(String shopId, Employee employee, String leaveTypeId,
      LeaveRequestValidator.Range range) {
    Plan plan = plan(shopId, leaveTypeId, range);
    BigDecimal available = null;
    boolean exceeds = false;
    if (LeaveBalanceService.needsBalanceCheck(plan.type())) {
      available = balanceService.balanceFor(shopId, employee, plan.type(), plan.leaveYear(),
          plan.settings().getLeaveYearStartMonth()).available();
      exceeds = plan.days().compareTo(available) > 0;
    }
    return new LeavePreviewResponse(plan.days(), Boolean.TRUE.equals(plan.type().getPaid()),
        available, exceeds, plan.leaveYear());
  }

  // ---- create --------------------------------------------------------------------------------

  /**
   * {@code POST /leave-requests} (MARK_ATTENDANCE): leave recorded for an employee. Created
   * APPROVED with origin RECORDED (R6.6), so the month lock applies like an approval.
   */
  public LeaveRequestResponse record(String userId, String shopId, RecordLeaveRequest request) {
    guard.require(userId, shopId, HrPermission.MARK_ATTENDANCE);
    validator.requireEmployeeId(request.getEmployeeId());
    LeaveRequestValidator.Range range = validator.validate(request.getLeaveTypeId(),
        request.getFromDate(), request.getToDate(), request.getHalfDay(), request.getReason());
    return queryRunner.read("leave_record", () -> {
      Employee employee = requireEmployee(shopId, request.getEmployeeId());
      Plan plan = checkedPlan(shopId, employee, request.getLeaveTypeId(), range);
      // lock before the insert: an approved leave changes the month's paid days
      periodLockService.assertOpen(shopId, periodsOf(plan));
      Instant now = Instant.now();
      LeaveRequest leave = newRequest(shopId, userId, employee, plan, request.getReason(),
          LeaveOrigin.RECORDED, LeaveRequestStatus.APPROVED, now);
      leave.setDecidedAt(now);
      leave.setDecidedByUserId(userId);
      LeaveRequest saved = leaveRequestRepository.insert(leave);
      // the checks above and the insert are two steps: a concurrent create may have passed
      // them too, so recheck now that our row is visible and take it back on a clash
      recheckAfterWrite(shopId, employee, plan, saved, true, () -> deleteRequest(shopId, saved));
      long removed = cleanUpMarks(shopId, saved, plan.workingDates());
      audit(shopId, userId, HrAuditAction.LEAVE_APPROVED, saved, null, removed);
      if (employee.getUserId() != null && !employee.getUserId().equals(userId)) {
        notificationService.notifyUser(shopId, employee.getUserId(),
            HrNotificationType.LEAVE_APPROVED, saved.getId(),
            "Leave recorded for you: " + dates(saved));
      }
      return toResponse(saved, employee, plan.type().getName());
    });
  }

  /**
   * Self-service request (endpoint in task 10.1): PENDING, origin SELF_SERVICE, approvers are
   * notified. The employee is the caller's own, never one from the body.
   */
  public LeaveRequestResponse requestForSelf(String shopId, String userId,
      RequestLeaveRequest request) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    LeaveRequestValidator.Range range = validator.validate(request.getLeaveTypeId(),
        request.getFromDate(), request.getToDate(), request.getHalfDay(), request.getReason());
    return queryRunner.read("leave_request", () -> {
      Plan plan = checkedPlan(shopId, self, request.getLeaveTypeId(), range);
      LeaveRequest saved = leaveRequestRepository.insert(newRequest(shopId, userId, self, plan,
          request.getReason(), LeaveOrigin.SELF_SERVICE, LeaveRequestStatus.PENDING,
          Instant.now()));
      // pending days do not count against the balance, so only the overlap is rechecked
      recheckAfterWrite(shopId, self, plan, saved, false, () -> deleteRequest(shopId, saved));
      notificationService.notifyApprovers(shopId, HrNotificationType.LEAVE_REQUESTED,
          saved.getId(), self.getName() + " asked for leave: " + dates(saved));
      return toResponse(saved, self, plan.type().getName());
    });
  }

  // ---- decide --------------------------------------------------------------------------------

  /**
   * PENDING → APPROVED (APPROVE_LEAVE). Order: lock check, recompute days and balance, then claim
   * with {@code findAndModify} on status PENDING (a second approver gets 409), then the mark
   * clean-up. A finalise that claims the month after our lock check sees {@code decidedAt} after
   * its claim and recomputes (task 8.5).
   */
  public LeaveRequestResponse approve(String userId, String shopId, String id, String note) {
    guard.require(userId, shopId, HrPermission.APPROVE_LEAVE);
    validator.validateText("note", note);
    return queryRunner.read("leave_approve", () -> {
      LeaveRequest request = find(shopId, id);
      if (request.getStatus() != LeaveRequestStatus.PENDING) {
        throw stateConflict();
      }
      LeaveRequestValidator.Range range = new LeaveRequestValidator.Range(
          HrDateUtils.parseDate(request.getFromDate()), HrDateUtils.parseDate(request.getToDate()),
          Boolean.TRUE.equals(request.getHalfDay()));
      periodLockService.assertOpen(shopId, periodsOf(range.from(), range.to()));
      Employee employee = requireEmployee(shopId, request.getEmployeeId());
      // the exit date may have moved since the request was made
      assertEmployed(employee, range);
      // days are recomputed: a holiday or weekly-off change since the request moves them
      Plan plan = plan(shopId, request.getLeaveTypeId(), range);
      if (plan.days().signum() == 0) {
        throw new ValidationException(
            "These dates have no working days any more. Reject the request instead");
      }
      checkBalance(shopId, employee, plan);
      Instant now = Instant.now();
      LeaveRequest approved = mongoTemplate.findAndModify(
          Query.query(Criteria.where("_id").is(id).and("shopId").is(shopId).and("status")
              .is(LeaveRequestStatus.PENDING)),
          new Update().set("status", LeaveRequestStatus.APPROVED).set("days", plan.days())
              .set("leaveYear", plan.leaveYear()).set("decidedAt", now)
              .set("decidedByUserId", userId).set("decisionNote", trimToNull(note))
              .set("updatedAt", now),
          FindAndModifyOptions.options().returnNew(true), LeaveRequest.class);
      if (approved == null) {
        throw stateConflict();
      }
      // two approvers approving two paid requests of one employee can both pass the balance
      // check; with ours now counted, a negative balance means we lost: back to PENDING
      recheckAfterWrite(shopId, employee, plan, approved, true,
          () -> revertToPending(shopId, approved));
      long removed = cleanUpMarks(shopId, approved, plan.workingDates());
      audit(shopId, userId, HrAuditAction.LEAVE_APPROVED, approved, note, removed);
      notificationService.notifyUser(shopId, approved.getRequestedByUserId(),
          HrNotificationType.LEAVE_APPROVED, approved.getId(),
          "Your leave was approved: " + dates(approved));
      return toResponse(approved, employee, plan.type().getName());
    });
  }

  /** PENDING → REJECTED (APPROVE_LEAVE). A pending request never touched the month, so no lock. */
  public LeaveRequestResponse reject(String userId, String shopId, String id, String note) {
    guard.require(userId, shopId, HrPermission.APPROVE_LEAVE);
    validator.validateText("note", note);
    return queryRunner.read("leave_reject", () -> {
      Instant now = Instant.now();
      LeaveRequest rejected = mongoTemplate.findAndModify(
          Query.query(Criteria.where("_id").is(id).and("shopId").is(shopId).and("status")
              .is(LeaveRequestStatus.PENDING)),
          new Update().set("status", LeaveRequestStatus.REJECTED).set("decidedAt", now)
              .set("decidedByUserId", userId).set("decisionNote", trimToNull(note))
              .set("updatedAt", now),
          FindAndModifyOptions.options().returnNew(true), LeaveRequest.class);
      if (rejected == null) {
        find(shopId, id); // 404 for an unknown or foreign id
        throw stateConflict();
      }
      audit(shopId, userId, HrAuditAction.LEAVE_REJECTED, rejected, note, 0);
      notificationService.notifyUser(shopId, rejected.getRequestedByUserId(),
          HrNotificationType.LEAVE_REJECTED, rejected.getId(),
          "Your leave was not approved: " + dates(rejected));
      return toResponse(rejected, employeeNames(shopId, List.of(rejected.getEmployeeId()))
          .get(rejected.getEmployeeId()), leaveTypeNames(shopId,
          List.of(rejected.getLeaveTypeId())).get(rejected.getLeaveTypeId()));
    });
  }

  /** Cancel any PENDING or APPROVED request (APPROVE_LEAVE or MARK_ATTENDANCE). */
  public LeaveRequestResponse cancel(String userId, String shopId, String id, String note) {
    guard.requireOneOf(userId, shopId, HrPermission.APPROVE_LEAVE, HrPermission.MARK_ATTENDANCE);
    validator.validateText("note", note);
    return queryRunner.read("leave_cancel", () -> doCancel(userId, shopId, find(shopId, id), note));
  }

  /**
   * Self-service cancel of an own PENDING or APPROVED request (endpoint in task 10.1). A request
   * of another employee is a 404, the same as an unknown id.
   */
  public LeaveRequestResponse cancelOwn(String shopId, String userId, String id, String note) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    validator.validateText("note", note);
    return queryRunner.read("leave_cancel", () -> {
      LeaveRequest request = find(shopId, id);
      if (!self.getId().equals(request.getEmployeeId())) {
        throw new ResourceNotFoundException("Leave request", "id", id);
      }
      return doCancel(userId, shopId, request, note);
    });
  }

  /**
   * Cancelling APPROVED leave gives the days back and clears them from attendance (R6.11): both
   * follow from the status alone, because balances and the overlay are derived. Only the month
   * lock matters, and only for approved leave. {@code decidedAt} moves so a finalise recheck sees
   * the change.
   */
  private LeaveRequestResponse doCancel(String userId, String shopId, LeaveRequest request,
      String note) {
    LeaveRequestStatus observed = request.getStatus();
    if (!LIVE.contains(observed)) {
      throw stateConflict();
    }
    if (observed == LeaveRequestStatus.APPROVED) {
      periodLockService.assertOpen(shopId, periodsOf(HrDateUtils.parseDate(request.getFromDate()),
          HrDateUtils.parseDate(request.getToDate())));
    }
    Instant now = Instant.now();
    Update update = new Update().set("status", LeaveRequestStatus.CANCELLED)
        .set("decidedAt", now).set("decidedByUserId", userId).set("updatedAt", now);
    if (StringUtils.hasText(note)) {
      update.set("decisionNote", note.trim());
    }
    // claim on the status we checked: a concurrent approve or cancel makes this a 409
    LeaveRequest cancelled = mongoTemplate.findAndModify(
        Query.query(Criteria.where("_id").is(request.getId()).and("shopId").is(shopId)
            .and("status").is(observed)),
        update, FindAndModifyOptions.options().returnNew(true), LeaveRequest.class);
    if (cancelled == null) {
      throw stateConflict();
    }
    audit(shopId, userId, HrAuditAction.LEAVE_CANCELLED, cancelled, note, 0);
    return toResponse(cancelled, employeeNames(shopId, List.of(cancelled.getEmployeeId()))
        .get(cancelled.getEmployeeId()), leaveTypeNames(shopId,
        List.of(cancelled.getLeaveTypeId())).get(cancelled.getLeaveTypeId()));
  }

  // ---- rules ---------------------------------------------------------------------------------

  /** Plan plus every create-time rule: employment, overlap and balance. */
  private Plan checkedPlan(String shopId, Employee employee, String leaveTypeId,
      LeaveRequestValidator.Range range) {
    Plan plan = plan(shopId, leaveTypeId, range);
    if (Boolean.FALSE.equals(plan.type().getActive())) {
      throw new ValidationException("This leave type is no longer in use");
    }
    assertEmployed(employee, range);
    if (plan.days().signum() == 0) {
      throw new ValidationException("These dates are all week-offs or holidays");
    }
    assertNoOverlap(shopId, employee.getId(), range);
    checkBalance(shopId, employee, plan);
    return plan;
  }

  /** Leave type, settings and holidays of the range: three reads. */
  Plan plan(String shopId, String leaveTypeId, LeaveRequestValidator.Range range) {
    LeaveType type = leaveTypeRepository.findByShopIdAndId(shopId, leaveTypeId)
        .orElseThrow(() -> new ResourceNotFoundException("Leave type", "id", leaveTypeId));
    HrSettings settings = settingsService.effectiveSettings(shopId);
    String leaveYear = LeaveYearUtils.leaveYearOf(range.from(), settings.getLeaveYearStartMonth());
    if (!leaveYear.equals(LeaveYearUtils.leaveYearOf(range.to(),
        settings.getLeaveYearStartMonth()))) {
      // one request, one leave year: balances group by year, so split it at the boundary
      throw new ValidationException(
          "A leave request cannot run into the next leave year. Split it in two");
    }
    Set<String> holidays = holidayDates(shopId, range.from(), range.to());
    List<LocalDate> working = LeaveDayCounter.workingDates(range.from(), range.to(), holidays,
        settings);
    return new Plan(type, settings, working, LeaveDayCounter.days(working, range.halfDay()),
        leaveYear, range.from(), range.to(), range.halfDay());
  }

  private static void assertEmployed(Employee employee, LeaveRequestValidator.Range range) {
    if (!DayStatusResolver.isEmployed(range.from(), employee)
        || !DayStatusResolver.isEmployed(range.to(), employee)) {
      throw new BaseException(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED,
          employee.getCode() + " is not employed on every day of this leave");
    }
  }

  /**
   * Second look after a create or approve (no transactions, P8): overlap with any other live
   * request of the employee, and, when {@code balance} is set, a paid balance below zero now that
   * this request counts. On a clash {@code undo} takes our write back and the call fails; when two
   * racers both see the clash both are undone, which is safe (the user simply retries).
   */
  private void recheckAfterWrite(String shopId, Employee employee, Plan plan, LeaveRequest saved,
      boolean balance, Runnable undo) {
    if (overlaps(shopId, employee.getId(), new LeaveRequestValidator.Range(plan.from(),
        plan.to(), plan.halfDay()), saved.getId())) {
      undo.run();
      throw new BaseException(ErrorCode.HR_LEAVE_OVERLAP, ErrorCode.HR_LEAVE_OVERLAP.getMessage());
    }
    if (balance && saved.getStatus() == LeaveRequestStatus.APPROVED
        && LeaveBalanceService.needsBalanceCheck(plan.type())
        && balanceService.balanceFor(shopId, employee, plan.type(), plan.leaveYear(),
            plan.settings().getLeaveYearStartMonth()).available().signum() < 0) {
      undo.run();
      throw new BaseException(ErrorCode.HR_LEAVE_BALANCE_EXCEEDED,
          "Not enough " + plan.type().getName() + " left for " + plan.leaveYear()
              + ". Choose an unpaid leave type for the extra days");
    }
  }

  private void deleteRequest(String shopId, LeaveRequest saved) {
    mongoTemplate.remove(Query.query(Criteria.where("_id").is(saved.getId()).and("shopId")
        .is(shopId)), LeaveRequest.class);
  }

  private void revertToPending(String shopId, LeaveRequest approved) {
    mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(approved.getId())
            .and("shopId").is(shopId).and("status").is(LeaveRequestStatus.APPROVED)),
        new Update().set("status", LeaveRequestStatus.PENDING).unset("decidedAt")
            .unset("decidedByUserId").unset("decisionNote").set("updatedAt", Instant.now()),
        LeaveRequest.class);
  }

  /** No other PENDING or APPROVED request of the employee may share a date (R6, design). */
  private void assertNoOverlap(String shopId, String employeeId,
      LeaveRequestValidator.Range range) {
    if (overlaps(shopId, employeeId, range, null)) {
      throw new BaseException(ErrorCode.HR_LEAVE_OVERLAP, ErrorCode.HR_LEAVE_OVERLAP.getMessage());
    }
  }

  private boolean overlaps(String shopId, String employeeId, LeaveRequestValidator.Range range,
      String excludeId) {
    // requests are at most 90 days, so an overlapping one starts no earlier than from − 89
    String lowest = HrDateUtils.format(range.from().minusDays(LeaveRequestValidator.MAX_DAYS - 1));
    Criteria c = Criteria.where("shopId").is(shopId).and("employeeId").is(employeeId)
        .and("status").in(LIVE)
        .and("fromDate").gte(lowest).lte(HrDateUtils.format(range.to()))
        .and("toDate").gte(HrDateUtils.format(range.from()));
    if (excludeId != null) {
      c = c.and("_id").ne(excludeId);
    }
    Query query = Query.query(c);
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.exists(query, LeaveRequest.class);
  }

  /** R6.13: a paid type with a quota may not go over the balance; unpaid types have no limit. */
  private void checkBalance(String shopId, Employee employee, Plan plan) {
    if (!LeaveBalanceService.needsBalanceCheck(plan.type())) {
      return;
    }
    BigDecimal available = balanceService.balanceFor(shopId, employee, plan.type(),
        plan.leaveYear(), plan.settings().getLeaveYearStartMonth()).available();
    if (plan.days().compareTo(available) > 0) {
      BigDecimal left = available.signum() < 0 ? BigDecimal.ZERO : available;
      throw new BaseException(ErrorCode.HR_LEAVE_BALANCE_EXCEEDED,
          "Only " + left.stripTrailingZeros().toPlainString() + " days of "
              + plan.type().getName() + " are left for " + plan.leaveYear()
              + ". Choose an unpaid leave type for the extra days");
    }
  }

  /**
   * Deletes stored full-day marks on the leave's working days (D4 clean-up). Idempotent, and
   * skipped for half-day leave, whose other half keeps its mark. Marks on the range's week-offs
   * and holidays stay: they record work on an off day.
   */
  private long cleanUpMarks(String shopId, LeaveRequest leave, List<LocalDate> workingDates) {
    if (Boolean.TRUE.equals(leave.getHalfDay()) || workingDates.isEmpty()) {
      return 0;
    }
    List<String> dates = workingDates.stream().map(HrDateUtils::format).toList();
    return mongoTemplate.remove(Query.query(Criteria.where("shopId").is(shopId)
        .and("employeeId").is(leave.getEmployeeId()).and("date").in(dates)),
        AttendanceDay.class).getDeletedCount();
  }

  // ---- reads ---------------------------------------------------------------------------------

  private LeaveRequest find(String shopId, String id) {
    return leaveRequestRepository.findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("Leave request", "id", id));
  }

  /** The employee of this shop, with only what leave needs; another shop's id is a 404. */
  private Employee requireEmployee(String shopId, String employeeId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(employeeId));
    query.fields().include("code", "name", "joinDate", "exitDate", "createdAt", "userId",
        "status");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Employee employee = mongoTemplate.findOne(query, Employee.class);
    if (employee == null) {
      throw new ResourceNotFoundException("Employee", "id", employeeId);
    }
    return employee;
  }

  private Map<String, Employee> employeeNames(String shopId, Collection<String> ids) {
    Map<String, Employee> byId = new HashMap<>();
    if (ids.isEmpty()) {
      return byId;
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id")
        .in(new TreeSet<>(ids)));
    query.fields().include("code", "name");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    for (Employee e : mongoTemplate.find(query, Employee.class)) {
      byId.put(e.getId(), e);
    }
    return byId;
  }

  /** Leave type names by id in one read (name only); unknown ids are left out. */
  private Map<String, String> leaveTypeNames(String shopId, Collection<String> ids) {
    Map<String, String> byId = new HashMap<>();
    Set<String> wanted = new TreeSet<>();
    for (String id : ids) {
      if (id != null) {
        wanted.add(id);
      }
    }
    if (wanted.isEmpty()) {
      return byId;
    }
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").in(wanted));
    query.fields().include("name");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    for (LeaveType t : mongoTemplate.find(query, LeaveType.class)) {
      byId.put(t.getId(), t.getName());
    }
    return byId;
  }

  private Set<String> holidayDates(String shopId, LocalDate from, LocalDate to) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("date")
        .gte(HrDateUtils.format(from)).lte(HrDateUtils.format(to)));
    query.fields().include("date");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Set<String> dates = new TreeSet<>();
    mongoTemplate.find(query, Holiday.class)
        .forEach(h -> dates.add(h.getDate()));
    return dates;
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static LeaveRequest newRequest(String shopId, String userId, Employee employee,
      Plan plan, String reason, LeaveOrigin origin, LeaveRequestStatus status, Instant now) {
    LeaveRequest r = new LeaveRequest();
    r.setShopId(shopId);
    r.setEmployeeId(employee.getId());
    r.setLeaveTypeId(plan.type().getId());
    r.setFromDate(HrDateUtils.format(plan.from()));
    r.setToDate(HrDateUtils.format(plan.to()));
    r.setHalfDay(plan.halfDay() ? Boolean.TRUE : null);
    r.setDays(plan.days());
    r.setReason(trimToNull(reason));
    r.setStatus(status);
    r.setRequestedByUserId(userId);
    r.setOrigin(origin);
    r.setLeaveYear(plan.leaveYear());
    r.setCreatedAt(now);
    r.setUpdatedAt(now);
    return r;
  }

  private static Collection<String> periodsOf(Plan plan) {
    return periodsOf(plan.from(), plan.to());
  }

  /** Every YYYY-MM the range touches (at most four for 90 days). */
  static Collection<String> periodsOf(LocalDate from, LocalDate to) {
    Set<String> periods = new TreeSet<>();
    for (LocalDate d = from.withDayOfMonth(1); !d.isAfter(to); d = d.plusMonths(1)) {
      periods.add(HrDateUtils.periodOf(HrDateUtils.format(d)));
    }
    return periods;
  }

  private void audit(String shopId, String userId, HrAuditAction action, LeaveRequest r,
      String note, long marksRemoved) {
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("status", r.getStatus().name());
    after.put("leaveTypeId", r.getLeaveTypeId());
    after.put("fromDate", r.getFromDate());
    after.put("toDate", r.getToDate());
    after.put("halfDay", Boolean.TRUE.equals(r.getHalfDay()));
    after.put("days", r.getDays() != null ? r.getDays().toPlainString() : null);
    after.put("origin", r.getOrigin() != null ? r.getOrigin().name() : null);
    if (marksRemoved > 0) {
      after.put("marksRemoved", marksRemoved);
    }
    auditService.record(new AuditEntry(shopId, userId, action, ENTITY, r.getId(),
        r.getEmployeeId(), r.getFromDate(), HrDateUtils.periodOf(r.getFromDate()), null, after,
        trimToNull(note)));
  }

  /** Dates only; notification texts never carry pay data. */
  private static String dates(LeaveRequest r) {
    String range = r.getFromDate().equals(r.getToDate()) ? r.getFromDate()
        : r.getFromDate() + " to " + r.getToDate();
    return Boolean.TRUE.equals(r.getHalfDay()) ? range + " (half day)" : range;
  }

  static LeaveRequestResponse toResponse(LeaveRequest r, Employee employee) {
    return toResponse(r, employee, null);
  }

  /** @param leaveTypeName null when the type is unknown (deleted or another shop's id) */
  static LeaveRequestResponse toResponse(LeaveRequest r, Employee employee,
      String leaveTypeName) {
    LeaveRequestResponse out = new LeaveRequestResponse();
    out.setId(r.getId());
    out.setEmployeeId(r.getEmployeeId());
    if (employee != null) {
      out.setEmployeeCode(employee.getCode());
      out.setEmployeeName(employee.getName());
    }
    out.setLeaveTypeId(r.getLeaveTypeId());
    out.setLeaveTypeName(leaveTypeName);
    out.setFromDate(r.getFromDate());
    out.setToDate(r.getToDate());
    out.setHalfDay(Boolean.TRUE.equals(r.getHalfDay()));
    out.setDays(r.getDays());
    out.setReason(r.getReason());
    out.setDecisionNote(r.getDecisionNote());
    out.setStatus(r.getStatus());
    out.setOrigin(r.getOrigin());
    out.setRequestedByUserId(r.getRequestedByUserId());
    out.setDecidedAt(r.getDecidedAt());
    out.setDecidedByUserId(r.getDecidedByUserId());
    out.setLeaveYear(r.getLeaveYear());
    out.setCreatedAt(r.getCreatedAt());
    return out;
  }

  private static LeaveRequestStatus parseStatus(String status) {
    try {
      return LeaveRequestStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Unknown leave status: " + status);
    }
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static BaseException stateConflict() {
    return new BaseException(ErrorCode.HR_LEAVE_STATE_CONFLICT,
        ErrorCode.HR_LEAVE_STATE_CONFLICT.getMessage());
  }
}
