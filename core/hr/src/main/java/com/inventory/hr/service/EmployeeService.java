package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.mapper.EmployeeMapper;
import com.inventory.hr.rest.dto.request.AddSalaryRevisionRequest;
import com.inventory.hr.rest.dto.request.ExitEmployeeRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.EmployeesPageResponse;
import com.inventory.hr.rest.dto.response.LinkableLoginResponse;
import com.inventory.hr.rest.dto.response.PageMeta;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.SalaryRevisionUtils;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.rest.dto.response.LoginContactDto;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Employees (R4): list and search, create with an auto code, update, exit, salary revisions and
 * the optional login link. Every read and write is scoped by the request's {@code shopId}; path
 * ids are looked up with {@code findByShopIdAndId}, so another shop's id is a 404.
 *
 * <p>Stockkart Team app (worker-app R4, R5, R8): the approval of a join request creates or links an
 * employee ({@link #createFromJoinRequest}, {@link #linkFromJoinRequest}); a signed-in worker
 * edits their own name and phone ({@link #updateSelfDetails}) and leaves the shop ({@link
 * #exitBySelf}) with {@code "self"} as the recorded actor; a manager asks a worker for their
 * profile details ({@link #requestProfile}).
 */
@Slf4j
@Service
public class EmployeeService {

  static final String ENTITY = "EMPLOYEE";
  /**
   * The actor recorded on a worker's own edit or exit (worker-app R5.3, R8.2): the row is theirs,
   * so a user id would say nothing a reader does not already know, and "self" tells the manager at
   * a glance that no colleague made the change.
   */
  public static final String SELF_ACTOR = "self";
  /** Audit flag on a self edit: the phone changed (the number itself never reaches a row). */
  static final String PHONE_CHANGED = "phoneChanged";
  static final int MAX_ACTIVE_EMPLOYEES = 1000;
  static final int DEFAULT_PAGE_SIZE = 20;
  static final int MAX_PAGE_SIZE = 100;
  static final int MAX_QUERY_LENGTH = 100;
  /** Auto codes skip numbers taken by hand-edited codes; give up after this many clashes. */
  static final int CODE_ATTEMPTS = 5;
  /** Index names from HrIndexMigration, used to tell which unique rule a duplicate broke. */
  static final String USER_INDEX = "shop_user_unique";

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private EmployeeMapper employeeMapper;

  @Autowired private EmployeeValidator validator;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrCounterService counterService;

  @Autowired private UserShopMembershipService membershipService;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private HrAuditService auditService;

  @Autowired private DevicePairingService pairingService;

  @Autowired private HrNotificationService notificationService;

  /**
   * Ends a WORKER membership when its employee exits (design DD14). Optional: {@code WorkerExitJob}
   * supplies the real one; without it the exit save behaves as before the Stockkart Team app.
   */
  @Autowired(required = false) private WorkerMembershipEnder membershipEnder =
      WorkerMembershipEnder.NONE;

  /** Search by name, code or phone ({@code q}), filter by status and designation, by name. */
  public EmployeesPageResponse list(String userId, String shopId, String q, String status,
      String designation, Integer page, Integer size) {
    return list(userId, shopId, q, status, designation, null, page, size);
  }

  /**
   * As above, plus {@code paired} ({@code "true"} / {@code "false"} / null = all): only employees
   * with / without an active Attendance app phone (R8.12, R1.9 link). Every row carries {@code
   * appPaired}. Pairings are read once per call: the shop's active set when filtering, else the
   * page's employees in one {@code $in} read.
   */
  public EmployeesPageResponse list(String userId, String shopId, String q, String status,
      String designation, String paired, Integer page, Integer size) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.VIEW_EMPLOYEES);
    int p = page != null ? page : 0;
    int s = size != null ? size : DEFAULT_PAGE_SIZE;
    if (p < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    if (s < 1 || s > MAX_PAGE_SIZE) {
      throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
    Boolean pairedFilter = parsePaired(paired);
    Query query = Query.query(Criteria.where("shopId").is(shopId));
    if (StringUtils.hasText(status)) {
      query.addCriteria(Criteria.where("status").is(parseStatus(status)));
    }
    if (StringUtils.hasText(designation)) {
      query.addCriteria(Criteria.where("designation").is(designation.trim()));
    }
    if (StringUtils.hasText(q)) {
      if (q.length() > MAX_QUERY_LENGTH) {
        throw new ValidationException("Search text may have at most " + MAX_QUERY_LENGTH
            + " characters");
      }
      // quoted, so the user's text is matched literally and never runs as a regex
      Pattern contains = Pattern.compile(Pattern.quote(q.trim()), Pattern.CASE_INSENSITIVE);
      query.addCriteria(new Criteria().orOperator(
          Criteria.where("name").regex(contains),
          Criteria.where("code").regex(contains),
          Criteria.where("phone").regex(contains)));
    }
    Set<String> pairedIds = null;
    if (pairedFilter != null) {
      // the filter must apply before paging, so the shop's whole active set is read once and
      // reused below to mark the rows
      pairedIds = pairingService.activePairedEmployeeIds(shopId);
      query.addCriteria(pairedFilter
          ? Criteria.where("_id").in(pairedIds)
          : Criteria.where("_id").nin(pairedIds));
    }
    long total = mongoTemplate.count(query, Employee.class);
    query.with(PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, "name")));
    boolean canViewSalaries = perms.contains(HrPermission.VIEW_SALARIES);
    List<Employee> employees = mongoTemplate.find(query, Employee.class);
    Set<String> pairedOnPage = pairedIds != null
        ? pairedIds
        : pairingService.activePairingsByEmployee(shopId,
            employees.stream().map(Employee::getId).toList()).keySet();
    List<EmployeeResponse> rows = employees.stream()
        .map(e -> {
          EmployeeResponse row = employeeMapper.toListRow(e, canViewSalaries);
          row.setAppPaired(pairedOnPage.contains(e.getId()));
          return row;
        })
        .toList();
    int totalPages = (int) ((total + s - 1) / s);
    return new EmployeesPageResponse(rows, new PageMeta(p, s, total, totalPages));
  }

  public EmployeeResponse get(String userId, String shopId, String id) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.VIEW_EMPLOYEES);
    EmployeeResponse r = employeeMapper.toDetail(find(shopId, id),
        perms.contains(HrPermission.VIEW_SALARIES));
    r.setAppPaired(pairingService.activePairing(shopId, id).isPresent());
    return r;
  }

  public EmployeeResponse create(String userId, String shopId, SaveEmployeeRequest request) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    boolean canViewSalaries = perms.contains(HrPermission.VIEW_SALARIES);
    validator.validate(request, true);
    if (!canViewSalaries && (sendsPay(request) || sendsPaymentDetails(request))) {
      throw salariesDenied();
    }
    if (employeeRepository.countByShopIdAndStatus(shopId, EmployeeStatus.ACTIVE)
        >= MAX_ACTIVE_EMPLOYEES) {
      throw new BaseException(ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT,
          ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT.getMessage());
    }
    String link = trimToNull(request.getUserId());
    if (link != null) {
      assertMayChangeLink(userId, shopId, perms, link);
      assertLinkable(shopId, link, null);
    }
    Instant now = Instant.now();
    Employee employee = new Employee();
    employee.setShopId(shopId);
    applyProfile(employee, request);
    // a new record has no payslips yet, so its first email needs only ADD_EDIT_EMPLOYEES
    employee.setEmail(trimToNull(request.getEmail()));
    employee.setUserId(link);
    employee.setStatus(EmployeeStatus.ACTIVE);
    employee.setSalaryRevisions(new ArrayList<>());
    if (request.getPayType() != null) {
      // default: the salary month (PayCycle) of the join date, else the current one
      int startDay = periodLockService.startDay(shopId);
      String from = request.getSalaryEffectiveFrom() != null
          ? request.getSalaryEffectiveFrom()
          : request.getJoinDate() != null
              ? PayCycle.periodOf(request.getJoinDate(), startDay)
              : PayCycle.currentPeriod(startDay);
      employee.getSalaryRevisions().add(new Employee.SalaryRevision(
          from, request.getPayType(), request.getSalaryAmount(), now, userId));
    }
    applyPaymentDetails(employee, request);
    employee.setCreatedAt(now);
    employee.setCreatedByUserId(userId);
    employee.setUpdatedAt(now);
    employee.setUpdatedByUserId(userId);
    Employee saved = insertWithCode(employee, request.getCode(), shopId);
    audit(shopId, userId, saved, null, snapshot(saved));
    if (link != null) {
      auditLink(shopId, userId, saved, null, link);
    }
    return employeeMapper.toDetail(saved, canViewSalaries);
  }

  /**
   * Full update of the profile and login link. Payment details are replaced only for a caller
   * with VIEW_SALARIES; without it they must be sent as null and keep their stored value. Pay
   * itself changes only through {@link #addSalaryRevision}.
   *
   * <p>The login link and the email decide who receives the employee's pay data (self-service
   * and emailed payslips), so both are guarded like payment details: a different {@code userId}
   * (including null = unlink) needs VIEW_SALARIES, and linking the caller's own login needs the
   * owner. Without VIEW_SALARIES, {@code email} null (or the stored address) keeps the stored
   * address and any other address is 403; with it, {@code email} is replaced (null clears).
   */
  public EmployeeResponse update(String userId, String shopId, String id,
      SaveEmployeeRequest request) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    boolean canViewSalaries = perms.contains(HrPermission.VIEW_SALARIES);
    validator.validate(request, false);
    if (sendsPay(request)) {
      throw new ValidationException("Use a salary revision to change pay");
    }
    if (!canViewSalaries && sendsPaymentDetails(request)) {
      throw salariesDenied();
    }
    Employee employee = find(shopId, id);
    if (request.getRevision() == null) {
      throw new ValidationException("revision is required: send the revision you last read");
    }
    if (!request.getRevision().equals(employee.getRevision())) {
      throw concurrentUpdate();
    }
    // the client's revision goes onto the entity, so @Version also catches an edit that lands
    // between this read and the save
    employee.setRevision(request.getRevision());
    Map<String, Object> before = snapshot(employee);
    String code = request.getCode().trim();
    if (!code.equals(employee.getCode()) && employeeRepository.existsByShopIdAndCode(shopId, code)) {
      throw codeTaken();
    }
    String link = trimToNull(request.getUserId());
    String previousLink = employee.getUserId();
    boolean linkChanged = !Objects.equals(link, previousLink);
    if (linkChanged) {
      assertMayChangeLink(userId, shopId, perms, link);
      if (link != null) {
        assertLinkable(shopId, link, employee.getId());
      }
    }
    String previousEmail = employee.getEmail();
    String email = trimToNull(request.getEmail());
    if (!canViewSalaries) {
      // same rule as payment details: null keeps the stored value; a real change is 403
      if (email != null && !email.equalsIgnoreCase(previousEmail)) {
        throw new BaseException(ErrorCode.ACCESS_DENIED,
            "You need 'view salaries' to change an employee's email");
      }
      email = previousEmail;
    }
    boolean emailChanged = !Objects.equals(email, previousEmail);
    applyProfile(employee, request);
    employee.setEmail(email);
    employee.setCode(code);
    employee.setUserId(link);
    if (canViewSalaries) {
      applyPaymentDetails(employee, request);
    }
    Employee saved = save(employee, userId, shopId);
    audit(shopId, userId, saved, before, snapshot(saved));
    if (linkChanged) {
      auditLink(shopId, userId, saved, previousLink, link);
    }
    if (emailChanged) {
      // a flag only: the address itself is PII and never reaches an audit row
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("emailChanged", true);
      auditService.record(new AuditEntry(shopId, userId, HrAuditAction.EMPLOYEE_EMAIL_CHANGED,
          ENTITY, saved.getId(), saved.getId(), null, null, null, after, null));
    }
    return employeeMapper.toDetail(saved, canViewSalaries);
  }

  /**
   * Marks the employee EXITED from {@code exitDate} (R4.7, R4.11); the row stays for history. The
   * save also unpairs or schedules the unpair of the employee's Attendance app phone (R3.4).
   */
  public EmployeeResponse exit(String userId, String shopId, String id,
      ExitEmployeeRequest request) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    Employee employee = find(shopId, id);
    validator.validateExit(request != null ? request.getExitDate() : null,
        HrDateUtils.effectiveJoinDateText(employee));
    Map<String, Object> before = snapshot(employee);
    employee.setStatus(EmployeeStatus.EXITED);
    employee.setExitDate(request.getExitDate());
    Employee saved = save(employee, userId, shopId);
    audit(shopId, userId, saved, before, snapshot(saved));
    return employeeMapper.toDetail(saved, perms.contains(HrPermission.VIEW_SALARIES));
  }

  // ---- Stockkart Team: join-request approval (worker-app R4.5, R4.8, R4.9; design DD12, DD13) ----

  /**
   * "Create new" on an approved join request: a new ACTIVE employee built from the request's
   * <em>snapshot</em> (name, phone, email as they were at request time, R3.9), joined today
   * (Asia/Kolkata), with the next auto code and linked to the requesting account. Every other
   * field is empty: no designation, hours, pay or payment details; the manager fills them in
   * later. Audited like {@link #create}: one {@code EMPLOYEE_CHANGED} row and one {@code
   * EMPLOYEE_LINK_CHANGED} row, both with the approver as actor.
   *
   * <p>No permission check and no {@link #assertMayChangeLink} here, on purpose. The caller
   * ({@code WorkerJoinService.approve}) has already required {@code ADD_EDIT_EMPLOYEES} and runs
   * this inside the approval transaction. The VIEW_SALARIES rule on the manual link exists
   * because a manager could point a record at some login that then reads its pay through My HR;
   * here the worker themself asked to be linked to their own record and a manager approved, which
   * is the case that rule guards against least (design DD13; open point 2 for the owner). The
   * login need not be a member of the shop yet either: the caller adds the WORKER membership in
   * the same transaction, after this returns.
   *
   * @return the inserted employee (the caller records its id on the request)
   * @throws BaseException {@code HR_ACTIVE_EMPLOYEE_LIMIT} when the shop is full, {@code
   *     HR_LOGIN_ALREADY_LINKED} when the account is already linked to an employee of this shop
   */
  public Employee createFromJoinRequest(String shopId, String approverId,
      WorkerJoinRequest request) {
    HrPermissionGuard.requireContext(approverId, shopId);
    if (request == null || request.getSnapshot() == null || request.getUserId() == null) {
      throw new ValidationException("A join request with a snapshot and an account is required");
    }
    if (employeeRepository.countByShopIdAndStatus(shopId, EmployeeStatus.ACTIVE)
        >= MAX_ACTIVE_EMPLOYEES) {
      throw new BaseException(ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT,
          ErrorCode.HR_ACTIVE_EMPLOYEE_LIMIT.getMessage());
    }
    WorkerJoinRequest.Snapshot snapshot = request.getSnapshot();
    Instant now = Instant.now();
    Employee employee = new Employee();
    employee.setShopId(shopId);
    employee.setName(snapshot.getName() != null ? snapshot.getName().trim() : null);
    employee.setPhone(trimToNull(snapshot.getPhone()));
    employee.setEmail(trimToNull(snapshot.getEmail()));
    employee.setUserId(request.getUserId());
    employee.setJoinDate(HrDateUtils.format(HrDateUtils.today()));
    employee.setStatus(EmployeeStatus.ACTIVE);
    employee.setSalaryRevisions(new ArrayList<>());
    employee.setCreatedAt(now);
    employee.setCreatedByUserId(approverId);
    employee.setUpdatedAt(now);
    employee.setUpdatedByUserId(approverId);
    Employee saved = insertWithCode(employee, null, shopId);
    audit(shopId, approverId, saved, null, snapshot(saved));
    auditLink(shopId, approverId, saved, null, request.getUserId());
    return saved;
  }

  /**
   * "Link to existing" on an approved join request: sets {@code userId} on the matched employee
   * and nothing else (R4.9), with one {@code EMPLOYEE_LINK_CHANGED} row. Succeeds only when the
   * employee is ACTIVE and unlinked, or already linked to this very account (then nothing is
   * written). Same permission stance as {@link #createFromJoinRequest}: the caller holds {@code
   * ADD_EDIT_EMPLOYEES}; {@link #assertMayChangeLink} is deliberately not applied.
   *
   * @param employee the possible match, read by the caller inside the transaction
   * @throws BaseException {@code HR_MATCH_EXITED} when the employee is not ACTIVE, {@code
   *     HR_MATCH_LINKED_ELSEWHERE} when it is linked to another login, {@code
   *     HR_LOGIN_ALREADY_LINKED} when this account is already linked to another employee here
   */
  public Employee linkFromJoinRequest(String shopId, String approverId, Employee employee,
      String userId) {
    HrPermissionGuard.requireContext(approverId, shopId);
    if (employee == null || !shopId.equals(employee.getShopId())) {
      throw new ResourceNotFoundException("Employee", "id",
          employee != null ? employee.getId() : null);
    }
    if (!StringUtils.hasText(userId)) {
      throw new ValidationException("An account to link is required");
    }
    if (employee.getStatus() != EmployeeStatus.ACTIVE) {
      throw new BaseException(ErrorCode.HR_MATCH_EXITED,
          String.format(ErrorCode.HR_MATCH_EXITED.getMessage(), employee.getCode()));
    }
    String previous = employee.getUserId();
    if (previous != null && !previous.equals(userId)) {
      throw new BaseException(ErrorCode.HR_MATCH_LINKED_ELSEWHERE,
          String.format(ErrorCode.HR_MATCH_LINKED_ELSEWHERE.getMessage(), employee.getCode()));
    }
    if (userId.equals(previous)) {
      return employee;
    }
    employee.setUserId(userId);
    Employee saved = save(employee, approverId, shopId);
    auditLink(shopId, approverId, saved, null, userId);
    return saved;
  }

  // ---- Stockkart Team app: the worker's own record (worker-app R5.3, R8.2, D12) -----------------

  /**
   * "Leave this shop": the caller's own employee becomes EXITED today (Asia/Kolkata), recorded as
   * done by {@link #SELF_ACTOR}; the manager may change the date later like any exit. The save
   * unpairs the phone (HR R3.4) and ends the WORKER membership through {@link
   * WorkerMembershipEnder} (R5.2). {@code userId} stays on the row so payslips remain reachable.
   * An employee already EXITED is returned unchanged.
   *
   * @throws BaseException {@code HR_NOT_LINKED} when the login has no employee in the shop
   */
  public Employee exitBySelf(String shopId, String userId) {
    Employee employee = own(shopId, userId);
    if (employee.getStatus() == EmployeeStatus.EXITED) {
      return employee;
    }
    Map<String, Object> before = snapshot(employee);
    employee.setStatus(EmployeeStatus.EXITED);
    employee.setExitDate(HrDateUtils.format(HrDateUtils.today()));
    Employee saved = save(employee, SELF_ACTOR, shopId);
    audit(shopId, SELF_ACTOR, saved, before, snapshot(saved));
    return saved;
  }

  /**
   * "Edit details" from the Stockkart Team app: name and phone only, under the R1.2 rules, with
   * {@link #SELF_ACTOR} as actor and one {@code EMPLOYEE_CHANGED} row whose {@code before}/{@code
   * after} carry the old and new name; the phone is PII and never reaches an audit row, so a changed
   * number is recorded as the flag {@code phoneChanged}. Nothing else on the row changes. The
   * account side of the edit is the user module's; the caller does both.
   *
   * @return the saved employee, or empty when the login has no employee in the shop (the
   *     account edit alone is then the whole change)
   */
  public Optional<Employee> updateSelfDetails(String shopId, String userId, String name,
      String phone) {
    EmployeeValidator.SelfDetails details = validator.validateSelfDetails(name, phone);
    HrPermissionGuard.requireContext(userId, shopId);
    Optional<Employee> found = employeeRepository.findByShopIdAndUserId(shopId, userId);
    if (found.isEmpty()) {
      return Optional.empty();
    }
    Employee employee = found.get();
    Map<String, Object> before = snapshot(employee);
    boolean phoneChanged = !Objects.equals(details.phone(), employee.getPhone());
    employee.setName(details.name());
    employee.setPhone(details.phone());
    Employee saved = save(employee, SELF_ACTOR, shopId);
    Map<String, Object> after = snapshot(saved);
    if (phoneChanged) {
      after.put(PHONE_CHANGED, true);
    }
    audit(shopId, SELF_ACTOR, saved, before, after);
    return Optional.of(saved);
  }

  /**
   * "Request profile details" (worker-app R8.7): stamps {@code profileRequestedAt}, writes a
   * {@code PROFILE_REQUESTED} history row and a {@code PROFILE_REQUESTED} notification to the
   * linked account. Asking again moves the stamp to now. Needs {@code ADD_EDIT_EMPLOYEES}.
   *
   * @throws ValidationException when the employee has no linked login: there is nobody to ask
   */
  public EmployeeResponse requestProfile(String approverId, String shopId, String employeeId) {
    Set<HrPermission> perms = guard.require(approverId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    Employee employee = find(shopId, employeeId);
    if (employee.getUserId() == null) {
      throw new ValidationException("This employee has no linked login to ask");
    }
    Instant previous = employee.getProfileRequestedAt();
    employee.setProfileRequestedAt(Instant.now());
    Employee saved = save(employee, approverId, shopId);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("profileRequestedAt", previous != null ? previous.toString() : null);
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("profileRequestedAt", saved.getProfileRequestedAt().toString());
    auditService.record(new AuditEntry(shopId, approverId, HrAuditAction.PROFILE_REQUESTED,
        ENTITY, saved.getId(), saved.getId(), null, null, before, after, null));
    notificationService.notifyUser(saved.getUserId(), HrNotificationType.PROFILE_REQUESTED,
        WorkerNotificationTexts.profileRequested(pairingService.shopName(shopId)), null);
    EmployeeResponse r = employeeMapper.toDetail(saved, perms.contains(HrPermission.VIEW_SALARIES));
    r.setAppPaired(pairingService.activePairing(shopId, saved.getId()).isPresent());
    return r;
  }

  /** The caller's own employee in the shop; 404 {@code HR_NOT_LINKED} when there is none. */
  private Employee own(String shopId, String userId) {
    HrPermissionGuard.requireContext(userId, shopId);
    return employeeRepository.findByShopIdAndUserId(shopId, userId)
        .orElseThrow(() -> new BaseException(ErrorCode.HR_NOT_LINKED,
            ErrorCode.HR_NOT_LINKED.getMessage()));
  }

  /**
   * New pay from a month on (R4.12). Earlier months keep their amount because each period reads
   * the latest revision with {@code effectiveFrom <= period}; a revision for the same month
   * replaces that month's entry. Needs VIEW_SALARIES as well as ADD_EDIT_EMPLOYEES.
   */
  public EmployeeResponse addSalaryRevision(String userId, String shopId, String id,
      AddSalaryRevisionRequest request) {
    guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES, HrPermission.VIEW_SALARIES);
    validator.validateRevision(request);
    periodLockService.assertOpen(shopId, request.getEffectiveFrom());
    Employee employee = find(shopId, id);
    Map<String, Object> before = snapshot(employee);
    List<Employee.SalaryRevision> revisions = employee.getSalaryRevisions() != null
        ? new ArrayList<>(employee.getSalaryRevisions()) : new ArrayList<>();
    revisions.removeIf(r -> request.getEffectiveFrom().equals(r.getEffectiveFrom()));
    revisions.add(new Employee.SalaryRevision(request.getEffectiveFrom(), request.getPayType(),
        request.getAmount(), Instant.now(), userId));
    SalaryRevisionUtils.sort(revisions);
    employee.setSalaryRevisions(revisions);
    Employee saved = save(employee, userId, shopId);
    audit(shopId, userId, saved, before, snapshot(saved));
    return employeeMapper.toDetail(saved, true);
  }

  /** Distinct designations of the shop, for the filter. */
  public List<String> designations(String userId, String shopId) {
    guard.require(userId, shopId, HrPermission.VIEW_EMPLOYEES);
    return mongoTemplate
        .findDistinct(Query.query(Criteria.where("shopId").is(shopId)), "designation",
            Employee.class, String.class)
        .stream()
        .filter(StringUtils::hasText)
        .sorted(String.CASE_INSENSITIVE_ORDER)
        .toList();
  }

  /**
   * Active logins of the shop and which employee each is linked to (login picker, R4.6). A caller
   * with VIEW_SALARIES (who may link) also gets each login's email and phone, read in one batch,
   * to prefill a new employee; for anyone else both stay null.
   */
  public List<LinkableLoginResponse> linkableLogins(String userId, String shopId) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    Map<String, String> linked = new HashMap<>();
    employeeRepository.findLinkedByShopId(shopId).forEach(e -> linked.put(e.getUserId(), e.getId()));
    List<ShopMemberSummaryDto> members = membershipService.listActiveMembers(shopId);
    Map<String, LoginContactDto> contacts = perms.contains(HrPermission.VIEW_SALARIES)
        ? membershipService.contactsOf(members.stream().map(ShopMemberSummaryDto::getUserId)
            .toList())
        : Map.of();
    return members.stream()
        .map(m -> {
          LoginContactDto c = contacts.get(m.getUserId());
          return new LinkableLoginResponse(m.getUserId(), m.getName(), m.getRole(),
              linked.get(m.getUserId()), c != null ? c.getEmail() : null,
              c != null ? c.getPhone() : null);
        })
        .toList();
  }

  private Employee find(String shopId, String id) {
    return employeeRepository.findByShopIdAndId(shopId, id)
        .orElseThrow(() -> new ResourceNotFoundException("Employee", "id", id));
  }

  /**
   * A linked login reads the employee's pay and payslips through /hr/me, so setting, changing
   * or clearing a link needs VIEW_SALARIES (the owner has it), and pointing a record at the
   * caller's own login needs the owner: otherwise a VIEW_SALARIES member could link themselves
   * to a colleague's record.
   */
  private void assertMayChangeLink(String userId, String shopId, Set<HrPermission> perms,
      String link) {
    if (!perms.contains(HrPermission.VIEW_SALARIES)) {
      throw new BaseException(ErrorCode.ACCESS_DENIED,
          "You need 'view salaries' to link or unlink a login");
    }
    if (userId.equals(link)) {
      guard.requireOwner(userId, shopId);
    }
  }

  /**
   * One login per employee per shop: the login must be an active member of this shop and not be
   * linked to another employee. The partial unique (shopId, userId) index backs this up when two
   * saves race.
   */
  private void assertLinkable(String shopId, String link, String selfId) {
    boolean member = membershipService.listActiveMembers(shopId).stream()
        .map(ShopMemberSummaryDto::getUserId)
        .anyMatch(link::equals);
    if (!member) {
      throw new ValidationException("This login is not an active member of this shop");
    }
    employeeRepository.findByShopIdAndUserId(shopId, link)
        .filter(other -> !other.getId().equals(selfId))
        .ifPresent(other -> {
          throw loginLinked();
        });
  }

  /**
   * Inserts with the requested code, or with the next {@code EMP-nnn}. An auto code that a
   * hand-edited code already took is skipped (up to {@link #CODE_ATTEMPTS} tries); a requested
   * code that is taken is rejected at once.
   */
  private Employee insertWithCode(Employee employee, String requestedCode, String shopId) {
    boolean auto = !StringUtils.hasText(requestedCode);
    for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
      String code = auto ? counterService.nextEmployeeCode(shopId) : requestedCode.trim();
      if (employeeRepository.existsByShopIdAndCode(shopId, code)) {
        if (!auto) {
          throw codeTaken();
        }
        continue;
      }
      employee.setCode(code);
      try {
        return employeeRepository.insert(employee);
      } catch (DuplicateKeyException e) {
        if (isLoginClash(e)) {
          throw loginLinked();
        }
        if (!auto) {
          throw codeTaken();
        }
        // a concurrent create took the same code between the check and the insert: next one
      } catch (DataAccessException e) {
        log.error("Database error while creating an employee for shop {}: {}", shopId,
            e.getMessage());
        throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving the employee");
      }
    }
    throw codeTaken();
  }

  private Employee save(Employee employee, String userId, String shopId) {
    employee.setUpdatedAt(Instant.now());
    employee.setUpdatedByUserId(userId);
    Employee saved;
    try {
      saved = employeeRepository.save(employee);
    } catch (OptimisticLockingFailureException e) {
      throw concurrentUpdate();
    } catch (DuplicateKeyException e) {
      throw isLoginClash(e) ? loginLinked() : codeTaken();
    } catch (DataAccessException e) {
      log.error("Database error while saving employee {} for shop {}: {}", employee.getId(), shopId,
          e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving the employee");
    }
    syncExitUnpair(saved);
    syncWorkerMembership(saved);
    return saved;
  }

  /**
   * Ends the linked WORKER account's membership when the save leaves the employee EXITED with an
   * exit date today or earlier (worker-app R5.2, design DD14): the worker is free to join another
   * shop within the save instead of within the job's minute. A later exit date is left to {@code
   * WorkerExitJob}, which also finishes any call here that failed. Like {@link #syncExitUnpair},
   * a failure is logged and does not undo the save.
   */
  private void syncWorkerMembership(Employee saved) {
    if (saved.getStatus() != EmployeeStatus.EXITED || saved.getUserId() == null
        || saved.getMembershipEndedAt() != null) {
      return;
    }
    LocalDate exit = HrDateUtils.parseDate(saved.getExitDate());
    if (exit == null || exit.isAfter(HrDateUtils.today())) {
      return;
    }
    try {
      membershipEnder.onExit(saved);
    } catch (RuntimeException e) {
      log.error("Could not end the WORKER membership of employee {} (shop {}) after its exit: {}",
          saved.getId(), saved.getShopId(), e.getMessage());
    }
  }

  /**
   * Keeps the Attendance app pairing in step with the employee's exit (R3.4, R3.5): {@code EXITED}
   * with an exit date today or earlier unpairs the phone now; a later date schedules the unpair for
   * 00:00 Asia/Kolkata of that day (a changed date moves it); any other status clears a pending
   * schedule. The employee is already saved, so a failure here is logged and does not undo the
   * save; a due unpair that failed is finished by {@code PairingExitJob} within a minute.
   */
  private void syncExitUnpair(Employee saved) {
    try {
      if (saved.getStatus() == EmployeeStatus.EXITED) {
        pairingService.scheduleExitUnpair(saved.getShopId(), saved.getId(),
            HrDateUtils.parseDate(saved.getExitDate()));
      } else {
        pairingService.clearExitUnpair(saved.getShopId(), saved.getId());
      }
    } catch (RuntimeException e) {
      log.error("Could not update the Attendance app pairing of employee {} (shop {}) after its "
          + "save: {}", saved.getId(), saved.getShopId(), e.getMessage());
    }
  }

  /** Profile fields anyone with ADD_EDIT_EMPLOYEES may set; email is applied by the caller. */
  private static void applyProfile(Employee employee, SaveEmployeeRequest request) {
    employee.setName(request.getName().trim());
    employee.setPhone(trimToNull(request.getPhone()));
    employee.setDesignation(trimToNull(request.getDesignation()));
    employee.setJoinDate(request.getJoinDate());
    employee.setWorkStart(request.getWorkStart());
    employee.setWorkEnd(request.getWorkEnd());
  }

  private static void applyPaymentDetails(Employee employee, SaveEmployeeRequest request) {
    employee.setPaymentMethod(
        request.getPaymentMethod() != null ? request.getPaymentMethod() : HrPaymentMethod.CASH);
    employee.setBankAccountNumber(trimToNull(request.getBankAccountNumber()));
    String ifsc = trimToNull(request.getIfsc());
    employee.setIfsc(ifsc != null ? ifsc.toUpperCase() : null);
    employee.setUpiId(trimToNull(request.getUpiId()));
  }

  private static boolean sendsPay(SaveEmployeeRequest r) {
    return r.getPayType() != null || r.getSalaryAmount() != null
        || r.getSalaryEffectiveFrom() != null;
  }

  private static boolean sendsPaymentDetails(SaveEmployeeRequest r) {
    return r.getPaymentMethod() != null || StringUtils.hasText(r.getBankAccountNumber())
        || StringUtils.hasText(r.getIfsc()) || StringUtils.hasText(r.getUpiId());
  }

  /**
   * Audit view of an employee. It deliberately includes the payment and pay keys: {@link
   * HrAuditService} removes them and stores only "paymentDetailsChanged"/"salaryChanged" flags.
   * Phone and email are left out entirely.
   */
  static Map<String, Object> snapshot(Employee e) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", e.getName());
    m.put("code", e.getCode());
    m.put("designation", e.getDesignation());
    m.put("status", String.valueOf(e.getStatus()));
    m.put("joinDate", e.getJoinDate());
    m.put("exitDate", e.getExitDate());
    m.put("userId", e.getUserId());
    m.put("workStart", e.getWorkStart());
    m.put("workEnd", e.getWorkEnd());
    m.put("paymentMethod", String.valueOf(e.getPaymentMethod()));
    m.put("bankAccountNumber", e.getBankAccountNumber());
    m.put("ifsc", e.getIfsc());
    m.put("upiId", e.getUpiId());
    m.put("salaryRevisions", e.getSalaryRevisions() == null ? List.of()
        : e.getSalaryRevisions().stream()
            .map(r -> r.getEffectiveFrom() + ":" + r.getPayType() + ":"
                + (r.getAmount() != null ? r.getAmount().toPlainString() : ""))
            .toList());
    return m;
  }

  private void audit(String shopId, String userId, Employee saved, Map<String, Object> before,
      Map<String, Object> after) {
    auditService.record(new AuditEntry(shopId, userId, HrAuditAction.EMPLOYEE_CHANGED, ENTITY,
        saved.getId(), saved.getId(), null, null, before, after, null));
  }

  /** Separate row for a login link change, so it stands out from a profile edit. */
  private void auditLink(String shopId, String userId, Employee saved, String from, String to) {
    auditService.record(new AuditEntry(shopId, userId, HrAuditAction.EMPLOYEE_LINK_CHANGED,
        ENTITY, saved.getId(), saved.getId(), null, null, linkView(from), linkView(to), null));
  }

  private static Map<String, Object> linkView(String linkedUserId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("linked", linkedUserId != null ? "yes" : "no");
    m.put("userId", linkedUserId);
    return m;
  }

  private static EmployeeStatus parseStatus(String status) {
    try {
      return EmployeeStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ValidationException("status must be ACTIVE or EXITED");
    }
  }

  /** {@code ?paired=}: null or blank = no filter; anything but true / false is a 400. */
  static Boolean parsePaired(String paired) {
    if (!StringUtils.hasText(paired)) {
      return null;
    }
    String v = paired.trim().toLowerCase();
    if ("true".equals(v)) {
      return Boolean.TRUE;
    }
    if ("false".equals(v)) {
      return Boolean.FALSE;
    }
    throw new ValidationException("paired must be true or false");
  }

  private static boolean isLoginClash(DuplicateKeyException e) {
    return e.getMessage() != null && e.getMessage().contains(USER_INDEX);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static BaseException salariesDenied() {
    return new BaseException(ErrorCode.ACCESS_DENIED,
        "You need 'view salaries' to set pay or payment details");
  }

  private static BaseException codeTaken() {
    return new BaseException(ErrorCode.HR_EMPLOYEE_CODE_TAKEN,
        ErrorCode.HR_EMPLOYEE_CODE_TAKEN.getMessage());
  }

  private static BaseException loginLinked() {
    return new BaseException(ErrorCode.HR_LOGIN_ALREADY_LINKED,
        ErrorCode.HR_LOGIN_ALREADY_LINKED.getMessage());
  }

  private static BaseException concurrentUpdate() {
    return new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
        ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
  }
}
