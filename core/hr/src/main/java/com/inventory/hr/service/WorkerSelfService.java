package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.WorkerProfileResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The signed-in worker's own data (worker-app R5.3, R5.6, R7.9, R8.1, R8.2, R8.4, R10.1, R11.2;
 * design DD3, Property 18): history, profile, profile edit, Leave this shop and notifications, every
 * one derived from the Bearer token's account and never from an employee or shop id in the request.
 *
 * <p>{@link #requireWorkerAccess} is the "who may do what" row for a staff account without an
 * employee (design table): {@code GET /me} answers {@code STAFF_NO_EMPLOYEE}, everything else under
 * {@code /hr/worker} answers {@code HR_WORKER_ONLY}. A WORKER account, and a staff account with a
 * linked ACTIVE employee, pass.
 *
 * <p>The history read is the HR-module self-service rule (R4.10, R8.9) over the linked employee
 * rather than a pairing: the same 31-day window, newest first, the same per-day "counted" mark, and
 * it includes punches made before the account was linked because they belong to the same employee
 * (R7.9). {@code AppPunchService} is left as the design lists it: unchanged.
 */
@Service
public class WorkerSelfService {

  /** The account inbox page the app reads (R10.1); the notification service clamps to its max. */
  static final int NOTIFICATIONS_LIMIT = 20;

  @Autowired private WorkerAccounts workerAccounts;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private PunchRepository punchRepository;

  @Autowired private EmployeeService employeeService;

  @Autowired private DevicePairingService pairingService;

  @Autowired private WorkerPairingService workerPairingService;

  @Autowired private WorkerStateService stateService;

  @Autowired private HrNotificationService notificationService;

  @Autowired private ShopNamePort shopNamePort;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  // ---- who may do what ------------------------------------------------------------------------

  /**
   * Lets a WORKER account, or a staff account with a linked ACTIVE employee, through; refuses a
   * staff account with no employee anywhere (R5.6).
   *
   * @return the account
   * @throws AuthenticationException {@code UNAUTHORIZED} when there is no such account
   * @throws BaseException {@code HR_WORKER_ONLY} for a staff account without an employee
   */
  public AccountView requireWorkerAccess(String userId) {
    AccountView account = account(userId);
    if (account.worker()) {
      return account;
    }
    List<Employee> linked = employeeRepository.findByUserIdAndStatus(userId, EmployeeStatus.ACTIVE);
    if (linked == null || linked.isEmpty()) {
      throw new BaseException(ErrorCode.HR_WORKER_ONLY, ErrorCode.HR_WORKER_ONLY.getMessage());
    }
    return account;
  }

  // ---- history (R7.9, R8.4) -------------------------------------------------------------------

  /**
   * The caller's own punches whose day lies in {@code [from, to]}, newest first, each marked counted
   * or not by the day's 5-minute rule; at most {@value AppPunchService#HISTORY_MAX_DAYS} days, the
   * last 31 including today when the range is left out. The employee is the caller's linked ACTIVE
   * one (R8.4); punches from before the link are included (R7.9).
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when the caller has no linked ACTIVE employee
   * @throws ValidationException for a malformed or too wide range
   */
  public List<PunchResponse> punches(String userId, String from, String to) {
    Employee mine = workerPairingService.linkedActiveEmployee(userId);
    LocalDate today = today(Instant.now(clock));
    LocalDate toDate = StringUtils.hasText(to) ? requireDate(to, "to") : today;
    LocalDate fromDate = StringUtils.hasText(from) ? requireDate(from, "from")
        : toDate.minusDays(AppPunchService.HISTORY_MAX_DAYS - 1);
    if (fromDate.isAfter(toDate)) {
      throw new ValidationException("from must not be after to");
    }
    if (fromDate.plusDays(AppPunchService.HISTORY_MAX_DAYS - 1).isBefore(toDate)) {
      throw new ValidationException(
          "The range may cover at most " + AppPunchService.HISTORY_MAX_DAYS + " days");
    }
    String shopId = mine.getShopId();
    String employeeId = mine.getId();
    List<Punch> punches = punchRepository.findRangeNewestFirst(shopId, employeeId,
        HrDateUtils.format(fromDate), HrDateUtils.format(toDate));

    // which punches count is decided per day; the work end plays no part in it
    Map<String, List<Punch>> byDay = new LinkedHashMap<>();
    for (Punch p : punches) {
      if (p.getLocalDate() != null) {
        byDay.computeIfAbsent(p.getLocalDate(), x -> new ArrayList<>()).add(p);
      }
    }
    Set<String> countedIds = new HashSet<>();
    for (Map.Entry<String, List<Punch>> e : byDay.entrySet()) {
      LocalDate day = HrDateUtils.parseDate(e.getKey());
      for (Punch c : PunchDayDeriver.derive(e.getValue(), day, day, LocalTime.MIN, null)
          .counted()) {
        countedIds.add(identity(c));
      }
    }
    List<PunchResponse> rows = new ArrayList<>(punches.size());
    for (Punch p : punches) {
      if (!employeeId.equals(p.getEmployeeId())) {
        // defence in depth (R8.4, R11.2): the query is scoped, but never return another's punch
        continue;
      }
      rows.add(new PunchResponse(p.getId(), p.getEmployeeId(), p.getAt(),
          p.getAt() != null ? PunchDayDeriver.hhmm(p.getAt()) : null, p.getLocalDate(),
          p.getSource(), p.getDirection(), p.getDistanceMetres(), p.getAccuracyMetres(),
          p.getFlags() != null ? List.copyOf(p.getFlags()) : List.of(),
          countedIds.contains(identity(p)), p.getDeviceModel()));
    }
    return rows;
  }

  // ---- profile (R8.1, R8.2) -------------------------------------------------------------------

  /**
   * The Profile screen (R8.1): the account's name, phone, e-mail and photo URL, and, when the
   * account is linked to an ACTIVE employee, the shop name, employee code, join date, the
   * "profile requested" flag (R8.7) and whether the phone is paired. Never pay, bank or UPI (R11.2).
   */
  public WorkerProfileResponse profile(String userId) {
    return profile(account(userId));
  }

  /**
   * "Edit details" (R8.2): the account first (the source of truth, under the R1.2 rules), then the
   * linked employee's name and phone with {@code self} as the actor and one audit row, when one
   * exists. The two validators apply the same rules (Property 1), so a value the account accepts the
   * employee accepts too. Nothing else changes: no request snapshot, punch or day (R8.3).
   *
   * @throws ValidationException when the name or phone breaks the rules; nothing is written
   */
  public WorkerProfileResponse updateProfile(String userId, String name, String phone) {
    requireUser(userId);
    AccountView saved = workerAccounts.updateProfile(userId, name, phone);
    linkedActiveEmployee(userId).ifPresent(e ->
        employeeService.updateSelfDetails(e.getShopId(), userId, name, phone));
    return profile(saved);
  }

  // ---- Leave this shop (R5.3) -----------------------------------------------------------------

  /**
   * "Leave this shop": the caller's linked ACTIVE employee becomes EXITED today, recorded as done
   * by {@code self}; the save unpairs the phone and ends the WORKER membership (R5.2). The answer
   * is the account's new state, which the app routes on (normally {@code NO_SHOP}).
   *
   * @throws BaseException {@code HR_WORKER_NOT_ACTIVE} when the caller has no linked ACTIVE employee
   */
  public WorkerStateResponse leave(String userId) {
    Employee mine = workerPairingService.linkedActiveEmployee(userId);
    employeeService.exitBySelf(mine.getShopId(), userId);
    return stateService.state(userId);
  }

  // ---- notifications (R10.1) ------------------------------------------------------------------

  /** The account's rows across shops, unread first then newest, at most 20, and the unread count. */
  public HrAccountNotificationsResponse notifications(String userId) {
    requireUser(userId);
    return notificationService.listForAccount(userId, NOTIFICATIONS_LIMIT);
  }

  /** Marks one of the caller's own rows read; 404 for any other row. */
  public void markRead(String userId, String notificationId) {
    requireUser(userId);
    notificationService.markRead(userId, notificationId);
  }

  // ---- helpers --------------------------------------------------------------------------------

  private WorkerProfileResponse profile(AccountView account) {
    WorkerProfileResponse r = new WorkerProfileResponse();
    r.setName(account.name());
    r.setPhone(account.phone());
    r.setEmail(account.email());
    r.setPhotoUrl(workerAccounts.hasPhoto(account.userId())
        ? String.format(WorkerJoinService.PHOTO_URL, account.userId()) : null);
    r.setPairingState(PairingStateResponse.State.NOT_PAIRED);
    linkedActiveEmployee(account.userId()).ifPresent(e -> {
      String name = shopNamePort.getShopName(e.getShopId());
      r.setShopName(StringUtils.hasText(name) ? name : "your shop");
      r.setEmployeeCode(e.getCode());
      r.setJoinDate(HrDateUtils.effectiveJoinDateText(e));
      r.setProfileRequested(e.getProfileRequestedAt() != null);
      if (pairingService.activePairing(e.getShopId(), e.getId()).isPresent()) {
        r.setPairingState(PairingStateResponse.State.PAIRED);
      }
    });
    return r;
  }

  /** The caller's linked ACTIVE employee, or empty; the same choice rule as the pairing service. */
  private Optional<Employee> linkedActiveEmployee(String userId) {
    try {
      return Optional.of(workerPairingService.linkedActiveEmployee(userId));
    } catch (BaseException e) {
      if (e.getErrorCode() == ErrorCode.HR_WORKER_NOT_ACTIVE) {
        return Optional.empty();
      }
      throw e;
    }
  }

  private AccountView account(String userId) {
    requireUser(userId);
    return workerAccounts.account(userId).orElseThrow(WorkerSelfService::notAuthenticated);
  }

  /** A stable handle for a punch read twice: its id, else its dedupe key. */
  private static String identity(Punch p) {
    return p.getId() != null ? "id:" + p.getId() : "key:" + p.getDedupeKey();
  }

  private static LocalDate requireDate(String value, String field) {
    LocalDate date = HrDateUtils.parseDate(value);
    if (date == null) {
      throw new ValidationException(field + " must be a date like 2025-04-07");
    }
    return date;
  }

  private static LocalDate today(Instant at) {
    return at.atZone(HrDateUtils.ZONE).toLocalDate();
  }

  private static void requireUser(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw notAuthenticated();
    }
  }

  private static AuthenticationException notAuthenticated() {
    return new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
  }
}
