package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
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

/**
 * Worker-app R5.3, R5.6, R7.9, R8.1, R8.2, R8.4, R10.1, R11.2: the signed-in worker's own data,
 * derived from the account only; the staff-without-employee rule; the 31-day history window with
 * the per-day "counted" mark and punches from before the link.
 */
@ExtendWith(MockitoExtension.class)
class WorkerSelfServiceTest {

  /** 2025-04-09 14:42 IST. */
  private static final Instant NOW = Instant.parse("2025-04-09T09:12:00Z");
  private static final String WORKER = "user-w";
  private static final String EMP = "emp-15";

  @Mock private WorkerAccounts workerAccounts;
  @Mock private EmployeeRepository employeeRepository;
  @Mock private PunchRepository punchRepository;
  @Mock private EmployeeService employeeService;
  @Mock private DevicePairingService pairingService;
  @Mock private WorkerPairingService workerPairingService;
  @Mock private WorkerStateService stateService;
  @Mock private HrNotificationService notificationService;
  @Mock private ShopNamePort shopNamePort;
  @InjectMocks private WorkerSelfService service;

  @BeforeEach
  void setUp() {
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    lenient().when(shopNamePort.getShopName(SHOP)).thenReturn("Sri Ganesh Stores");
    lenient().when(workerAccounts.hasPhoto(anyString())).thenReturn(false);
    lenient().when(pairingService.activePairing(anyString(), anyString()))
        .thenReturn(Optional.empty());
  }

  // ---- fixtures ------------------------------------------------------------------------------

  private static AccountView worker() {
    return new AccountView(WORKER, "Priya Sharma", "priya@example.com", "9876543210", "WORKER",
        SHOP, true);
  }

  private static AccountView cashier() {
    return new AccountView("user-c", "Ravi", "ravi@example.com", null, "CASHIER", SHOP, false);
  }

  private static Employee employee(String userId) {
    Employee e = new Employee();
    e.setId(EMP);
    e.setShopId(SHOP);
    e.setCode("EMP-015");
    e.setName("Priya Sharma");
    e.setUserId(userId);
    e.setJoinDate("2025-04-01");
    e.setStatus(EmployeeStatus.ACTIVE);
    return e;
  }

  private static Punch punch(String id, String employeeId, Instant at, String day) {
    Punch p = new Punch();
    p.setId(id);
    p.setShopId(SHOP);
    p.setEmployeeId(employeeId);
    p.setAt(at);
    p.setLocalDate(day);
    return p;
  }

  private void signedIn(AccountView account) {
    when(workerAccounts.account(account.userId())).thenReturn(Optional.of(account));
  }

  private void linked(Employee employee) {
    when(workerPairingService.linkedActiveEmployee(WORKER)).thenReturn(employee);
  }

  private void notLinked(String userId) {
    when(workerPairingService.linkedActiveEmployee(userId)).thenThrow(new BaseException(
        ErrorCode.HR_WORKER_NOT_ACTIVE, ErrorCode.HR_WORKER_NOT_ACTIVE.getMessage()));
  }

  // ---- who may do what (R5.6) ----------------------------------------------------------------

  @Test
  void aWorkerAccountPassesWithoutAnEmployeeLookup() {
    signedIn(worker());

    AccountView account = service.requireWorkerAccess(WORKER);

    assertEquals(WORKER, account.userId());
    verifyNoInteractions(employeeRepository);
  }

  @Test
  void aStaffAccountWithALinkedActiveEmployeePasses() {
    signedIn(cashier());
    when(employeeRepository.findByUserIdAndStatus("user-c", EmployeeStatus.ACTIVE))
        .thenReturn(List.of(employee("user-c")));

    assertEquals("user-c", service.requireWorkerAccess("user-c").userId());
  }

  @Test
  void aStaffAccountWithoutAnEmployeeIsWorkerOnly() {
    signedIn(cashier());
    when(employeeRepository.findByUserIdAndStatus("user-c", EmployeeStatus.ACTIVE))
        .thenReturn(List.of());

    BaseException e = assertThrows(BaseException.class,
        () -> service.requireWorkerAccess("user-c"));

    assertEquals(ErrorCode.HR_WORKER_ONLY, e.getErrorCode());
    assertEquals("This app is for workers. Ask your manager to link your login to your employee"
        + " record from the Employees page.", e.getMessage());
  }

  @Test
  void anUnknownOrBlankCallerIsNotAuthenticated() {
    when(workerAccounts.account("ghost")).thenReturn(Optional.empty());

    assertThrows(AuthenticationException.class, () -> service.requireWorkerAccess("ghost"));
    assertThrows(AuthenticationException.class, () -> service.requireWorkerAccess(" "));
    assertThrows(AuthenticationException.class, () -> service.profile(null));
    assertThrows(AuthenticationException.class, () -> service.notifications(""));
  }

  // ---- history (R7.9, R8.4) ------------------------------------------------------------------

  @Test
  void punchesDefaultToTheLast31DaysIncludingTodayNewestFirstWithTheCountedMark() {
    linked(employee(WORKER));
    // 14:42 IST and 14:44 IST today: the second is within 5 minutes and does not count;
    // yesterday's one is from before the link and still belongs to the employee
    Punch today1 = punch("p1", EMP, NOW, "2025-04-09");
    Punch today2 = punch("p2", EMP, NOW.plus(Duration.ofMinutes(2)), "2025-04-09");
    Punch beforeLink = punch("p0", EMP, NOW.minus(Duration.ofDays(1)), "2025-04-08");
    when(punchRepository.findRangeNewestFirst(SHOP, EMP, "2025-03-10", "2025-04-09"))
        .thenReturn(List.of(today2, today1, beforeLink));

    List<PunchResponse> rows = service.punches(WORKER, null, null);

    assertEquals(List.of("p2", "p1", "p0"), rows.stream().map(PunchResponse::getId).toList());
    assertFalse(rows.get(0).isCounted());
    assertTrue(rows.get(1).isCounted());
    assertTrue(rows.get(2).isCounted());
    assertEquals("14:42", rows.get(1).getTime());
    assertEquals(EMP, rows.get(2).getEmployeeId());
  }

  @Test
  void punchesTakeAnExplicitRangeAndDropAnyRowOfAnotherEmployee() {
    linked(employee(WORKER));
    when(punchRepository.findRangeNewestFirst(SHOP, EMP, "2025-04-01", "2025-04-09"))
        .thenReturn(List.of(punch("p1", EMP, NOW, "2025-04-09"),
            punch("px", "emp-99", NOW, "2025-04-09")));

    List<PunchResponse> rows = service.punches(WORKER, "2025-04-01", "2025-04-09");

    assertEquals(1, rows.size());
    assertEquals("p1", rows.get(0).getId());
  }

  @Test
  void punchesRefuseAWiderRangeThan31DaysAndAnInvertedOne() {
    linked(employee(WORKER));

    ValidationException wide = assertThrows(ValidationException.class,
        () -> service.punches(WORKER, "2025-03-01", "2025-04-09"));
    assertEquals("The range may cover at most 31 days", wide.getMessage());
    assertThrows(ValidationException.class,
        () -> service.punches(WORKER, "2025-04-09", "2025-04-01"));
    assertThrows(ValidationException.class,
        () -> service.punches(WORKER, "2025/04/01", null));
    verifyNoInteractions(punchRepository);
  }

  @Test
  void punchesWithoutALinkedEmployeeAreRefusedAsNotActive() {
    notLinked(WORKER);

    BaseException e = assertThrows(BaseException.class,
        () -> service.punches(WORKER, null, null));

    assertEquals(ErrorCode.HR_WORKER_NOT_ACTIVE, e.getErrorCode());
    verifyNoInteractions(punchRepository);
  }

  // ---- profile (R8.1, R8.2, R11.2) ------------------------------------------------------------

  @Test
  void profileOfALinkedWorkerCarriesTheShopEmployeeAndPairingState() {
    signedIn(worker());
    Employee e = employee(WORKER);
    e.setProfileRequestedAt(NOW.minus(Duration.ofDays(1)));
    linked(e);
    when(workerAccounts.hasPhoto(WORKER)).thenReturn(true);
    when(pairingService.activePairing(SHOP, EMP)).thenReturn(Optional.of(new DevicePairing()));

    WorkerProfileResponse p = service.profile(WORKER);

    assertEquals("Priya Sharma", p.getName());
    assertEquals("9876543210", p.getPhone());
    assertEquals("priya@example.com", p.getEmail());
    assertEquals("/api/v1/users/" + WORKER + "/photo", p.getPhotoUrl());
    assertEquals("Sri Ganesh Stores", p.getShopName());
    assertEquals("EMP-015", p.getEmployeeCode());
    assertEquals("2025-04-01", p.getJoinDate());
    assertTrue(p.isProfileRequested());
    assertEquals(PairingStateResponse.State.PAIRED, p.getPairingState());
  }

  @Test
  void profileOfAWorkerWithoutAShopHasTheAccountOnly() {
    signedIn(worker());
    notLinked(WORKER);

    WorkerProfileResponse p = service.profile(WORKER);

    assertEquals("Priya Sharma", p.getName());
    assertNull(p.getPhotoUrl());
    assertNull(p.getShopName());
    assertNull(p.getEmployeeCode());
    assertNull(p.getJoinDate());
    assertFalse(p.isProfileRequested());
    assertEquals(PairingStateResponse.State.NOT_PAIRED, p.getPairingState());
  }

  @Test
  void profileNamesTheShopYourShopWhenItHasNoName() {
    signedIn(worker());
    linked(employee(WORKER));
    when(shopNamePort.getShopName(SHOP)).thenReturn(null);

    assertEquals("your shop", service.profile(WORKER).getShopName());
  }

  @Test
  void updateProfileWritesTheAccountThenTheLinkedEmployeeAndAnswersTheSavedAccount() {
    AccountView saved = new AccountView(WORKER, "Priya S", "priya@example.com", "9876543211",
        "WORKER", SHOP, true);
    when(workerAccounts.updateProfile(WORKER, "Priya S", "+91 98765 43211")).thenReturn(saved);
    linked(employee(WORKER));
    when(employeeService.updateSelfDetails(SHOP, WORKER, "Priya S", "+91 98765 43211"))
        .thenReturn(Optional.of(employee(WORKER)));

    WorkerProfileResponse p = service.updateProfile(WORKER, "Priya S", "+91 98765 43211");

    assertEquals("Priya S", p.getName());
    assertEquals("9876543211", p.getPhone());
    assertEquals("EMP-015", p.getEmployeeCode());
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(workerAccounts, employeeService);
    order.verify(workerAccounts).updateProfile(WORKER, "Priya S", "+91 98765 43211");
    order.verify(employeeService).updateSelfDetails(SHOP, WORKER, "Priya S", "+91 98765 43211");
  }

  @Test
  void updateProfileWithoutALinkedEmployeeChangesTheAccountOnly() {
    AccountView saved = new AccountView(WORKER, "Priya S", "priya@example.com", "9876543211",
        "WORKER", null, true);
    when(workerAccounts.updateProfile(WORKER, "Priya S", "9876543211")).thenReturn(saved);
    notLinked(WORKER);

    WorkerProfileResponse p = service.updateProfile(WORKER, "Priya S", "9876543211");

    assertEquals("Priya S", p.getName());
    verify(employeeService, never()).updateSelfDetails(any(), any(), any(), any());
  }

  @Test
  void updateProfileRefusedByTheAccountRulesWritesNothingToTheEmployee() {
    when(workerAccounts.updateProfile(WORKER, "P", "12345"))
        .thenThrow(new ValidationException("Enter a 10-digit mobile number."));

    assertThrows(ValidationException.class, () -> service.updateProfile(WORKER, "P", "12345"));

    verifyNoInteractions(employeeService, workerPairingService);
  }

  // ---- Leave this shop (R5.3) ----------------------------------------------------------------

  @Test
  void leaveExitsTheOwnEmployeeAndAnswersTheNewState() {
    linked(employee(WORKER));
    WorkerStateResponse after = new WorkerStateResponse();
    after.setState(WorkerStateResponse.State.NO_SHOP);
    when(stateService.state(WORKER)).thenReturn(after);

    WorkerStateResponse answer = service.leave(WORKER);

    assertSame(after, answer);
    org.mockito.InOrder order = org.mockito.Mockito.inOrder(employeeService, stateService);
    order.verify(employeeService).exitBySelf(SHOP, WORKER);
    order.verify(stateService).state(WORKER);
  }

  @Test
  void leaveWithoutALinkedEmployeeIsRefusedAndExitsNobody() {
    notLinked(WORKER);

    BaseException e = assertThrows(BaseException.class, () -> service.leave(WORKER));

    assertEquals(ErrorCode.HR_WORKER_NOT_ACTIVE, e.getErrorCode());
    verifyNoInteractions(employeeService, stateService);
  }

  // ---- notifications (R10.1) -----------------------------------------------------------------

  @Test
  void notificationsReadTwentyAcrossShopsAndMarkReadIsTheAccountsOwnRow() {
    HrAccountNotificationsResponse page = new HrAccountNotificationsResponse(List.of(), 0);
    when(notificationService.listForAccount(WORKER, 20)).thenReturn(page);

    assertSame(page, service.notifications(WORKER));
    service.markRead(WORKER, "n1");
    verify(notificationService).markRead(WORKER, "n1");
    verify(notificationService, never()).markRead(eq(WORKER), anyString(), anyString());
  }
}
