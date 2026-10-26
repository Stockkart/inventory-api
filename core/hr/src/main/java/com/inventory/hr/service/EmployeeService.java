package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
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
import com.inventory.hr.utils.SalaryRevisionUtils;
import com.inventory.hr.validation.EmployeeValidator;
import com.inventory.user.rest.dto.response.LoginContactDto;
import com.inventory.user.rest.dto.response.ShopMemberSummaryDto;
import com.inventory.user.service.UserShopMembershipService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 */
@Slf4j
@Service
public class EmployeeService {

  static final String ENTITY = "EMPLOYEE";
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

  /** Search by name, code or phone ({@code q}), filter by status and designation, by name. */
  public EmployeesPageResponse list(String userId, String shopId, String q, String status,
      String designation, Integer page, Integer size) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.VIEW_EMPLOYEES);
    int p = page != null ? page : 0;
    int s = size != null ? size : DEFAULT_PAGE_SIZE;
    if (p < 0) {
      throw new ValidationException("page must be 0 or more");
    }
    if (s < 1 || s > MAX_PAGE_SIZE) {
      throw new ValidationException("size must be between 1 and " + MAX_PAGE_SIZE);
    }
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
    long total = mongoTemplate.count(query, Employee.class);
    query.with(PageRequest.of(p, s, Sort.by(Sort.Direction.ASC, "name")));
    boolean canViewSalaries = perms.contains(HrPermission.VIEW_SALARIES);
    List<EmployeeResponse> rows = mongoTemplate.find(query, Employee.class).stream()
        .map(e -> employeeMapper.toListRow(e, canViewSalaries))
        .toList();
    int totalPages = (int) ((total + s - 1) / s);
    return new EmployeesPageResponse(rows, new PageMeta(p, s, total, totalPages));
  }

  public EmployeeResponse get(String userId, String shopId, String id) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.VIEW_EMPLOYEES);
    return employeeMapper.toDetail(find(shopId, id), perms.contains(HrPermission.VIEW_SALARIES));
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
      String from = request.getSalaryEffectiveFrom() != null
          ? request.getSalaryEffectiveFrom()
          : request.getJoinDate() != null
              ? HrDateUtils.periodOf(request.getJoinDate())
              : HrDateUtils.currentPeriod();
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

  /** Marks the employee EXITED from {@code exitDate} (R4.7, R4.11); the row stays for history. */
  public EmployeeResponse exit(String userId, String shopId, String id,
      ExitEmployeeRequest request) {
    Set<HrPermission> perms = guard.require(userId, shopId, HrPermission.ADD_EDIT_EMPLOYEES);
    Employee employee = find(shopId, id);
    validator.validateExit(request != null ? request.getExitDate() : null,
        employee.getJoinDate());
    Map<String, Object> before = snapshot(employee);
    employee.setStatus(EmployeeStatus.EXITED);
    employee.setExitDate(request.getExitDate());
    Employee saved = save(employee, userId, shopId);
    audit(shopId, userId, saved, before, snapshot(saved));
    return employeeMapper.toDetail(saved, perms.contains(HrPermission.VIEW_SALARIES));
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
    try {
      return employeeRepository.save(employee);
    } catch (OptimisticLockingFailureException e) {
      throw concurrentUpdate();
    } catch (DuplicateKeyException e) {
      throw isLoginClash(e) ? loginLinked() : codeTaken();
    } catch (DataAccessException e) {
      log.error("Database error while saving employee {} for shop {}: {}", employee.getId(), shopId,
          e.getMessage());
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error saving the employee");
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
