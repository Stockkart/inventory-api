package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.HrNotificationResponse;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse.State;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * {@code GET /api/v1/hr/worker/me}: the one answer the Stockkart Team app reads on every
 * launch, foreground and Waiting-screen poll to choose its screen (worker-app R6.1, R3.10). Derived
 * from the Bearer token's account only; no shop, employee or request id is taken from the request
 * (R8.4, R11.2).
 *
 * <p>The state is decided in R6.1 order, each step only when the one before it did not apply:
 *
 * <ol>
 *   <li>a linked {@code ACTIVE} employee in any shop, whether the account is a WORKER or a staff
 *       member with a linked record (R5.6) → {@link State#ACTIVE};
 *   <li>else one open join request → {@link State#PENDING};
 *   <li>else a staff role (anything but WORKER) → {@link State#STAFF_NO_EMPLOYEE};
 *   <li>else → {@link State#NO_SHOP}, with the last rejection or withdrawal of the past 90 days so
 *       the Join screen can show it once (R4.12).
 * </ol>
 *
 * <p>The ACTIVE sections (shop, employee, modules, pairing, today) are present only for ACTIVE;
 * {@code modules} is {@code []} otherwise (R6.4). Today's in/out and next direction come from
 * today's punches through {@link PunchDayDeriver}, exactly as the device status call computes them
 * (R6.5). The banner stack is the account's unread rows across shops, newest first, at most three,
 * with the total still unread (R10.1).
 */
@Service
public class WorkerStateService {

  /** How far back the Join screen's "did not accept your request" card looks (R4.12). */
  static final Duration OUTCOME_WINDOW = Duration.ofDays(90);

  /** The banner stack shows at most this many rows (R10.1). */
  static final int TOP_NOTIFICATIONS = 3;

  /** Statuses that count as "a request is open" (the partial unique index's filter). */
  static final Set<WorkerJoinStatus> OPEN =
      EnumSet.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.APPROVING);

  /** Decisions the Join screen shows; an approval is never a "last outcome". */
  static final Set<WorkerJoinStatus> SHOWN_OUTCOMES =
      EnumSet.of(WorkerJoinStatus.REJECTED, WorkerJoinStatus.WITHDRAWN);

  @Autowired private WorkerAccounts workerAccounts;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private WorkerJoinRequestRepository requestRepository;

  @Autowired private DevicePairingService pairingService;

  @Autowired private PunchDayService punchDays;

  @Autowired private HrNotificationService notificationService;

  @Autowired private ShopNamePort shopNamePort;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  /**
   * The account's state and everything the app draws from it.
   *
   * @throws AuthenticationException {@code UNAUTHORIZED} when there is no such account
   */
  public WorkerStateResponse state(String userId) {
    if (!StringUtils.hasText(userId)) {
      throw notAuthenticated();
    }
    AccountView account = workerAccounts.account(userId)
        .orElseThrow(WorkerStateService::notAuthenticated);
    Instant now = Instant.now(clock);

    WorkerStateResponse r = new WorkerStateResponse();
    r.setAccount(account(account));
    r.setModules(List.of());
    r.setServerTime(now);

    Optional<Employee> linked = linkedActiveEmployee(account);
    if (linked.isPresent()) {
      fillActive(r, linked.get(), now);
    } else {
      Optional<WorkerJoinRequest> open =
          requestRepository.findFirstByUserIdAndStatusIn(userId, OPEN);
      if (open.isPresent()) {
        r.setState(State.PENDING);
        r.setRequest(self(open.get()));
      } else if (!account.worker()) {
        r.setState(State.STAFF_NO_EMPLOYEE);
      } else {
        r.setState(State.NO_SHOP);
        lastOutcome(userId, now).ifPresent(r::setLastOutcome);
      }
    }

    r.setNotifications(notifications(userId));
    return r;
  }

  // ---- steps ---------------------------------------------------------------------------------

  /**
   * The account's one linked ACTIVE employee, in any shop (R5.1 keeps it to one). Should a stale
   * document ever leave two, the one in the account's own shop wins, else the first found.
   */
  private Optional<Employee> linkedActiveEmployee(AccountView account) {
    List<Employee> active =
        employeeRepository.findByUserIdAndStatus(account.userId(), EmployeeStatus.ACTIVE);
    if (active == null || active.isEmpty()) {
      return Optional.empty();
    }
    if (account.shopId() != null) {
      for (Employee e : active) {
        if (account.shopId().equals(e.getShopId())) {
          return Optional.of(e);
        }
      }
    }
    return Optional.of(active.get(0));
  }

  private void fillActive(WorkerStateResponse r, Employee employee, Instant now) {
    String shopId = employee.getShopId();
    r.setState(State.ACTIVE);
    r.setShop(new WorkerStateResponse.Shop(shopId, shopName(shopId)));
    r.setEmployee(new WorkerStateResponse.EmployeeSummary(employee.getId(), employee.getCode(),
        employee.getName(), HrDateUtils.effectiveJoinDateText(employee), employee.getStatus()));
    r.setModules(WorkerModules.forShop(shopId));
    r.setPairing(pairing(shopId, employee.getId()));
    r.setToday(today(shopId, employee.getId(), now));
  }

  private WorkerStateResponse.Pairing pairing(String shopId, String employeeId) {
    Optional<DevicePairing> active = pairingService.activePairing(shopId, employeeId);
    return active.map(p -> new WorkerStateResponse.Pairing(PairingStateResponse.State.PAIRED,
            p.getId()))
        .orElseGet(() -> new WorkerStateResponse.Pairing(PairingStateResponse.State.NOT_PAIRED,
            null));
  }

  /**
   * The Attendance tile line (R6.5) from today's punches, Asia/Kolkata, by the same 5-minute rule
   * the device status call uses: in = first counted punch, out = last counted when two or more,
   * next direction IN until the day has a counted punch, then OUT (HR-module R4.9).
   */
  private WorkerStateResponse.Today today(String shopId, String employeeId, Instant now) {
    ZonedDateTime local = now.atZone(HrDateUtils.ZONE);
    LocalDate today = local.toLocalDate();
    String d = HrDateUtils.format(today);
    List<Punch> punches = punchDays.punchesOf(shopId, employeeId, d);
    // the work end only decides "missing check-out", which the tile does not show
    PunchDayDeriver.Derived derived =
        PunchDayDeriver.derive(punches, today, today, local.toLocalTime(), null);
    return new WorkerStateResponse.Today(d, derived.inTime(), derived.outTime(),
        derived.isEmpty() ? PunchDirection.IN : PunchDirection.OUT);
  }

  /** The last rejection or withdrawal decided within the window; nothing for an approval. */
  private Optional<WorkerStateResponse.LastOutcome> lastOutcome(String userId, Instant now) {
    return requestRepository
        .findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(userId, now.minus(OUTCOME_WINDOW))
        .filter(req -> SHOWN_OUTCOMES.contains(req.getStatus()))
        .map(req -> new WorkerStateResponse.LastOutcome(req.getId(), req.getStatus(),
            shopName(req.getShopId()), req.getRejectionReason(), req.getDecidedAt()));
  }

  /** The unread rows only, newest first, at most three, and the total unread (R10.1). */
  private WorkerStateResponse.Notifications notifications(String userId) {
    HrAccountNotificationsResponse page =
        notificationService.listForAccount(userId, TOP_NOTIFICATIONS);
    List<HrNotificationResponse> top = new ArrayList<>(TOP_NOTIFICATIONS);
    if (page != null && page.getItems() != null) {
      for (HrNotificationResponse n : page.getItems()) {
        if (!n.isRead() && top.size() < TOP_NOTIFICATIONS) {
          top.add(n);
        }
      }
    }
    return new WorkerStateResponse.Notifications(page != null ? page.getUnread() : 0, top);
  }

  // ---- mapping -------------------------------------------------------------------------------

  private WorkerStateResponse.Account account(AccountView a) {
    String photoUrl = workerAccounts.hasPhoto(a.userId())
        ? String.format(WorkerJoinService.PHOTO_URL, a.userId()) : null;
    return new WorkerStateResponse.Account(a.userId(), a.name(), a.email(), a.phone(), photoUrl,
        a.role());
  }

  private JoinRequestSelfResponse self(WorkerJoinRequest r) {
    return new JoinRequestSelfResponse(r.getId(), shopName(r.getShopId()), r.getStatus(),
        r.getRequestedAt());
  }

  /** The shop's display name, or "your shop" when the shop is unknown or has no name. */
  private String shopName(String shopId) {
    String name = shopId != null ? shopNamePort.getShopName(shopId) : null;
    return StringUtils.hasText(name) ? name : "your shop";
  }

  private static AuthenticationException notAuthenticated() {
    return new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
  }
}
