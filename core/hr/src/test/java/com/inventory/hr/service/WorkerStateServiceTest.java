package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.AuthenticationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.HrNotificationResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse.State;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Worker-app R3.10, R5.5, R5.6, R6.1, R6.4, R6.5, R10.1. */
@ExtendWith(MockitoExtension.class)
class WorkerStateServiceTest {

  /** 2025-04-09 14:42 IST. */
  private static final Instant NOW = Instant.parse("2025-04-09T09:12:00Z");
  private static final String WORKER = "user-w";
  private static final String EMP = "emp-15";

  @Mock private WorkerAccounts workerAccounts;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private WorkerJoinRequestRepository requestRepository;
  @Mock private DevicePairingService pairingService;
  @Mock private PunchDayService punchDays;
  @Mock private HrNotificationService notificationService;
  @Mock private ShopNamePort shopNamePort;
  @InjectMocks private WorkerStateService service;

  @BeforeEach
  void setUp() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    lenient().when(shopNamePort.getShopName(SHOP)).thenReturn("Sri Ganesh Stores");
    lenient().when(notificationService.listForAccount(anyString(), eq(3)))
        .thenReturn(new HrAccountNotificationsResponse(List.of(), 0));
    lenient().when(employeeRepository.findByUserIdAndStatus(anyString(), eq(EmployeeStatus.ACTIVE)))
        .thenReturn(List.of());
    lenient().when(requestRepository.findFirstByUserIdAndStatusIn(anyString(), any()))
        .thenReturn(Optional.empty());
    lenient().when(requestRepository
            .findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(anyString(), any()))
        .thenReturn(Optional.empty());
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static AccountView worker() {
    return new AccountView(WORKER, "Priya Sharma", "priya@example.com", "9876543210", "WORKER",
        null, true);
  }

  private static AccountView workerIn(String shopId) {
    return new AccountView(WORKER, "Priya Sharma", "priya@example.com", "9876543210", "WORKER",
        shopId, true);
  }

  private static AccountView cashier() {
    return new AccountView("user-c", "Ravi", "ravi@example.com", null, "CASHIER", SHOP, false);
  }

  private static Employee employee(String shopId, String userId) {
    Employee e = new Employee();
    e.setId(EMP);
    e.setShopId(shopId);
    e.setCode("EMP-015");
    e.setName("Priya Sharma");
    e.setUserId(userId);
    e.setJoinDate("2025-04-01");
    e.setStatus(EmployeeStatus.ACTIVE);
    return e;
  }

  private static WorkerJoinRequest pending() {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("req-1");
    r.setShopId(SHOP);
    r.setUserId(WORKER);
    r.setStatus(WorkerJoinStatus.PENDING);
    r.setRequestedAt(NOW.minus(Duration.ofHours(2)));
    return r;
  }

  private static WorkerJoinRequest decided(WorkerJoinStatus status, Instant decidedAt,
      String reason) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("req-0");
    r.setShopId(SHOP);
    r.setUserId(WORKER);
    r.setStatus(status);
    r.setDecidedAt(decidedAt);
    r.setRejectionReason(reason);
    return r;
  }

  private static Punch punch(String id, Instant at) {
    Punch p = new Punch();
    p.setId(id);
    p.setShopId(SHOP);
    p.setEmployeeId(EMP);
    p.setAt(at);
    p.setLocalDate("2025-04-09");
    return p;
  }

  private static HrNotificationResponse note(String id, boolean read) {
    return new HrNotificationResponse(id, HrNotificationType.JOIN_REQUEST_APPROVED, null, "req-1",
        "Sri Ganesh Stores accepted you", NOW, read);
  }

  private void signedIn(AccountView account) {
    when(workerAccounts.account(account.userId())).thenReturn(Optional.of(account));
    lenient().when(workerAccounts.hasPhoto(account.userId())).thenReturn(false);
  }

  private void active(AccountView account, Employee employee) {
    signedIn(account);
    when(employeeRepository.findByUserIdAndStatus(account.userId(), EmployeeStatus.ACTIVE))
        .thenReturn(List.of(employee));
    lenient().when(pairingService.activePairing(employee.getShopId(), employee.getId()))
        .thenReturn(Optional.empty());
    lenient().when(punchDays.punchesOf(employee.getShopId(), employee.getId(), "2025-04-09"))
        .thenReturn(List.of());
  }

  // ---- the four states, in R6.1 order --------------------------------------------------------

  @Test
  void noShopWhenNothingLinksTheAccountAnywhere() {
    signedIn(worker());

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.NO_SHOP, r.getState());
    assertEquals(WORKER, r.getAccount().getUserId());
    assertEquals("Priya Sharma", r.getAccount().getName());
    assertEquals("priya@example.com", r.getAccount().getEmail());
    assertEquals("9876543210", r.getAccount().getPhone());
    assertEquals("WORKER", r.getAccount().getRole());
    assertNull(r.getAccount().getPhotoUrl());
    assertNull(r.getRequest());
    assertNull(r.getLastOutcome());
    assertNull(r.getShop());
    assertNull(r.getEmployee());
    assertNull(r.getPairing());
    assertNull(r.getToday());
    assertEquals(List.of(), r.getModules());
    assertEquals(NOW, r.getServerTime());
    assertEquals(0, r.getNotifications().getUnread());
    assertEquals(List.of(), r.getNotifications().getTop());
  }

  @Test
  void pendingWhenOneRequestIsOpen() {
    signedIn(worker());
    when(requestRepository.findFirstByUserIdAndStatusIn(WORKER, WorkerStateService.OPEN))
        .thenReturn(Optional.of(pending()));

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.PENDING, r.getState());
    assertEquals("req-1", r.getRequest().getId());
    assertEquals("Sri Ganesh Stores", r.getRequest().getShopName());
    assertEquals(WorkerJoinStatus.PENDING, r.getRequest().getStatus());
    assertEquals(NOW.minus(Duration.ofHours(2)), r.getRequest().getRequestedAt());
    assertEquals(List.of(), r.getModules());
    assertNull(r.getLastOutcome());
    assertNull(r.getShop());
    assertNull(r.getToday());
    // a pending request never looks for a past decision
    verify(requestRepository, never())
        .findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(anyString(), any());
  }

  @Test
  void activeWhenALinkedActiveEmployeeExists() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    when(workerAccounts.hasPhoto(WORKER)).thenReturn(true);

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.ACTIVE, r.getState());
    assertEquals(SHOP, r.getShop().getShopId());
    assertEquals("Sri Ganesh Stores", r.getShop().getShopName());
    assertEquals(EMP, r.getEmployee().getEmployeeId());
    assertEquals("EMP-015", r.getEmployee().getCode());
    assertEquals("Priya Sharma", r.getEmployee().getName());
    assertEquals("2025-04-01", r.getEmployee().getJoinDate());
    assertEquals(EmployeeStatus.ACTIVE, r.getEmployee().getStatus());
    assertEquals(List.of("ATTENDANCE", "HISTORY", "PROFILE"), r.getModules());
    assertEquals("/api/v1/users/user-w/photo", r.getAccount().getPhotoUrl());
    assertNull(r.getRequest());
    assertNull(r.getLastOutcome());
    assertNotNull(r.getPairing());
    assertNotNull(r.getToday());
    // ACTIVE is decided before any request is read (R6.1 order)
    verify(requestRepository, never()).findFirstByUserIdAndStatusIn(anyString(), any());
  }

  @Test
  void activeBeatsAnOpenRequest() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    lenient().when(requestRepository.findFirstByUserIdAndStatusIn(WORKER, WorkerStateService.OPEN))
        .thenReturn(Optional.of(pending()));

    assertEquals(State.ACTIVE, service.state(WORKER).getState());
  }

  @Test
  void aStaffAccountWithALinkedEmployeeIsActiveLikeAWorker() {
    Employee e = employee(SHOP, "user-c");
    active(cashier(), e);

    WorkerStateResponse r = service.state("user-c");

    assertEquals(State.ACTIVE, r.getState());
    assertEquals("CASHIER", r.getAccount().getRole());
    assertEquals(List.of("ATTENDANCE", "HISTORY", "PROFILE"), r.getModules());
    assertNull(r.getAccount().getPhone());
  }

  @Test
  void aStaffAccountWithoutAnEmployeeIsBlockedNotOfferedJoin() {
    signedIn(cashier());

    WorkerStateResponse r = service.state("user-c");

    assertEquals(State.STAFF_NO_EMPLOYEE, r.getState());
    assertEquals(List.of(), r.getModules());
    assertNull(r.getRequest());
    assertNull(r.getLastOutcome());
    assertNull(r.getShop());
    assertNull(r.getEmployee());
    verify(requestRepository, never())
        .findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(anyString(), any());
  }

  @Test
  void aStaffAccountWithAnOpenRequestIsPendingBeforeBlocked() {
    signedIn(cashier());
    when(requestRepository.findFirstByUserIdAndStatusIn("user-c", WorkerStateService.OPEN))
        .thenReturn(Optional.of(pending()));

    assertEquals(State.PENDING, service.state("user-c").getState());
  }

  @Test
  void anExitedEmployeeDoesNotMakeTheAccountActive() {
    signedIn(worker());
    // the ACTIVE finder is the only one asked; an EXITED record is never returned by it
    when(employeeRepository.findByUserIdAndStatus(WORKER, EmployeeStatus.ACTIVE))
        .thenReturn(List.of());

    assertEquals(State.NO_SHOP, service.state(WORKER).getState());
    verify(employeeRepository, never()).findByUserIdAndStatus(WORKER, EmployeeStatus.EXITED);
  }

  @Test
  void theEmployeeInTheAccountsOwnShopWinsWhenTwoAreLinked() {
    Employee other = employee("shop-9", WORKER);
    other.setId("emp-99");
    Employee own = employee(SHOP, WORKER);
    signedIn(workerIn(SHOP));
    when(employeeRepository.findByUserIdAndStatus(WORKER, EmployeeStatus.ACTIVE))
        .thenReturn(List.of(other, own));
    when(pairingService.activePairing(SHOP, EMP)).thenReturn(Optional.empty());
    when(punchDays.punchesOf(SHOP, EMP, "2025-04-09")).thenReturn(List.of());

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(SHOP, r.getShop().getShopId());
    assertEquals(EMP, r.getEmployee().getEmployeeId());
  }

  // ---- lastOutcome window --------------------------------------------------------------------

  @Test
  void aRejection89DaysAgoIsShownWithItsReason() {
    signedIn(worker());
    Instant decidedAt = NOW.minus(Duration.ofDays(89));
    when(requestRepository.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(WORKER,
        NOW.minus(Duration.ofDays(90))))
        .thenReturn(Optional.of(decided(WorkerJoinStatus.REJECTED, decidedAt, "Not hiring now")));

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.NO_SHOP, r.getState());
    assertEquals("req-0", r.getLastOutcome().getId());
    assertEquals(WorkerJoinStatus.REJECTED, r.getLastOutcome().getStatus());
    assertEquals("Sri Ganesh Stores", r.getLastOutcome().getShopName());
    assertEquals("Not hiring now", r.getLastOutcome().getReason());
    assertEquals(decidedAt, r.getLastOutcome().getDecidedAt());
  }

  @Test
  void aRejection91DaysAgoIsOutsideTheWindow() {
    signedIn(worker());
    // the repository is asked only for decisions after now − 90 d, so the 91-day row is not found
    when(requestRepository.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(WORKER,
        NOW.minus(Duration.ofDays(90)))).thenReturn(Optional.empty());

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.NO_SHOP, r.getState());
    assertNull(r.getLastOutcome());
    verify(requestRepository).findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(WORKER,
        NOW.minus(Duration.ofDays(90)));
  }

  @Test
  void aWithdrawalIsShownWithoutAReason() {
    signedIn(worker());
    when(requestRepository.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(eq(WORKER),
        any())).thenReturn(Optional.of(decided(WorkerJoinStatus.WITHDRAWN,
        NOW.minus(Duration.ofDays(1)), null)));

    WorkerStateResponse.LastOutcome o = service.state(WORKER).getLastOutcome();

    assertEquals(WorkerJoinStatus.WITHDRAWN, o.getStatus());
    assertNull(o.getReason());
  }

  @Test
  void anApprovalIsNeverALastOutcome() {
    // approved, then left the shop: the Join screen shows no card
    signedIn(worker());
    when(requestRepository.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(eq(WORKER),
        any())).thenReturn(Optional.of(decided(WorkerJoinStatus.APPROVED,
        NOW.minus(Duration.ofDays(1)), null)));

    assertNull(service.state(WORKER).getLastOutcome());
  }

  @Test
  void lastOutcomeIsOnlyLookedUpInNoShop() {
    active(workerIn(SHOP), employee(SHOP, WORKER));

    assertNull(service.state(WORKER).getLastOutcome());
    verify(requestRepository, never())
        .findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(anyString(), any());
  }

  // ---- pairing -------------------------------------------------------------------------------

  @Test
  void notPairedWhenTheEmployeeHasNoActivePairing() {
    active(workerIn(SHOP), employee(SHOP, WORKER));

    WorkerStateResponse.Pairing p = service.state(WORKER).getPairing();

    assertEquals(PairingStateResponse.State.NOT_PAIRED, p.getState());
    assertNull(p.getPairingId());
  }

  @Test
  void pairedCarriesThePairingId() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    DevicePairing pairing = new DevicePairing();
    pairing.setId("pairing-7");
    when(pairingService.activePairing(SHOP, EMP)).thenReturn(Optional.of(pairing));

    WorkerStateResponse.Pairing p = service.state(WORKER).getPairing();

    assertEquals(PairingStateResponse.State.PAIRED, p.getState());
    assertEquals("pairing-7", p.getPairingId());
  }

  // ---- today's line --------------------------------------------------------------------------

  @Test
  void todayWithoutPunchesIsNotCheckedIn() {
    active(workerIn(SHOP), employee(SHOP, WORKER));

    WorkerStateResponse.Today t = service.state(WORKER).getToday();

    assertEquals("2025-04-09", t.getDate());
    assertNull(t.getIn());
    assertNull(t.getOut());
    assertEquals(PunchDirection.IN, t.getNextDirection());
  }

  @Test
  void todayWithOnePunchShowsInOnly() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    when(punchDays.punchesOf(SHOP, EMP, "2025-04-09"))
        .thenReturn(List.of(punch("p1", Instant.parse("2025-04-09T03:42:00Z"))));

    WorkerStateResponse.Today t = service.state(WORKER).getToday();

    assertEquals("09:12", t.getIn());
    assertNull(t.getOut());
    assertEquals(PunchDirection.OUT, t.getNextDirection());
  }

  @Test
  void todayWithInAndOutShowsBothAndIgnoresADuplicate() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    when(punchDays.punchesOf(SHOP, EMP, "2025-04-09")).thenReturn(List.of(
        punch("p1", Instant.parse("2025-04-09T03:42:00Z")),
        // 2 minutes later: a duplicate by the 5-minute rule, never the out time
        punch("p2", Instant.parse("2025-04-09T03:44:00Z")),
        punch("p3", Instant.parse("2025-04-09T12:35:00Z"))));

    WorkerStateResponse.Today t = service.state(WORKER).getToday();

    assertEquals("09:12", t.getIn());
    assertEquals("18:05", t.getOut());
    assertEquals(PunchDirection.OUT, t.getNextDirection());
  }

  @Test
  void todayIsReadOnlyForActive() {
    signedIn(worker());

    assertNull(service.state(WORKER).getToday());
    verify(punchDays, never()).punchesOf(anyString(), anyString(), anyString());
    verify(pairingService, never()).activePairing(anyString(), anyString());
  }

  // ---- notifications -------------------------------------------------------------------------

  @Test
  void bannerStackIsTheUnreadRowsNewestFirstAtMostThreeWithTheTotal() {
    signedIn(worker());
    when(notificationService.listForAccount(WORKER, 3)).thenReturn(
        new HrAccountNotificationsResponse(
            List.of(note("n5", false), note("n4", false), note("n3", false)), 5));

    WorkerStateResponse.Notifications n = service.state(WORKER).getNotifications();

    assertEquals(5, n.getUnread());
    assertEquals(List.of("n5", "n4", "n3"),
        n.getTop().stream().map(HrNotificationResponse::getId).toList());
    assertTrue(n.getTop().stream().noneMatch(HrNotificationResponse::isRead));
  }

  @Test
  void readRowsNeverFillTheBannerStack() {
    signedIn(worker());
    // one unread, the page padded with read rows: the stack shows the unread one only
    when(notificationService.listForAccount(WORKER, 3)).thenReturn(
        new HrAccountNotificationsResponse(
            List.of(note("n1", false), note("n0", true), note("n-1", true)), 1));

    WorkerStateResponse.Notifications n = service.state(WORKER).getNotifications();

    assertEquals(1, n.getUnread());
    assertEquals(1, n.getTop().size());
    assertEquals("n1", n.getTop().get(0).getId());
  }

  @Test
  void notificationsArePresentInEveryState() {
    active(workerIn(SHOP), employee(SHOP, WORKER));
    when(notificationService.listForAccount(WORKER, 3)).thenReturn(
        new HrAccountNotificationsResponse(List.of(note("n1", false)), 1));

    WorkerStateResponse r = service.state(WORKER);

    assertEquals(State.ACTIVE, r.getState());
    assertEquals(1, r.getNotifications().getUnread());
    assertFalse(r.getNotifications().getTop().isEmpty());
  }

  // ---- account -------------------------------------------------------------------------------

  @Test
  void anUnknownAccountIsNotAuthenticated() {
    when(workerAccounts.account("ghost")).thenReturn(Optional.empty());

    assertThrows(AuthenticationException.class, () -> service.state("ghost"));
    assertThrows(AuthenticationException.class, () -> service.state(null));
    assertThrows(AuthenticationException.class, () -> service.state(" "));
  }

  @Test
  void anUnknownShopNameFallsBackToYourShop() {
    active(workerIn("shop-x"), employee("shop-x", WORKER));
    when(shopNamePort.getShopName("shop-x")).thenReturn(null);

    assertEquals("your shop", service.state(WORKER).getShop().getShopName());
  }
}
