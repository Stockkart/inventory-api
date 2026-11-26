// Feature: worker-app, Property 13: Server-side worker state classification
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse.State;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 13 of the worker-app design: for any account role (WORKER or a staff role), shop
 * presence, linked employee status (none, ACTIVE, EXITED) and open-request presence, {@link
 * WorkerStateService#state} answers {@code ACTIVE} iff a linked ACTIVE employee exists; else {@code
 * PENDING} iff an open request exists; else {@code STAFF_NO_EMPLOYEE} iff the role is a staff role;
 * else {@code NO_SHOP}. {@code modules} is {@link WorkerModules#forShop} iff ACTIVE and empty
 * otherwise; {@code employee}, {@code shop}, {@code pairing} and {@code today} are present iff
 * ACTIVE. In addition {@code request} is present iff PENDING, and {@code lastOutcome} appears only
 * in NO_SHOP, for a rejection or withdrawal decided inside the 90-day window (R5.5: no trace of a
 * former shop on Home).
 *
 * <p>The collaborators are fakes over an in-memory picture of the account: the employee finder
 * answers by status exactly as the Mongo query would (an EXITED record is never returned for an
 * ACTIVE lookup), the request finders filter by status and {@code decidedAt}, and pairing and
 * punches are what the scenario says.
 *
 * <p><b>Validates: Requirements 5.5, 5.6, 6.1</b>
 */
class WorkerStateServiceProperties {

  /** 2025-04-09 14:42 IST. */
  private static final Instant NOW = Instant.parse("2025-04-09T09:12:00Z");

  /** Midnight of that day, Asia/Kolkata. */
  private static final Instant TODAY_START = Instant.parse("2025-04-08T18:30:00Z");

  private static final String USER = "user-w";
  private static final String OWN_SHOP = "shop-own";
  private static final String OTHER_SHOP = "shop-other";
  private static final String EMPLOYEE_ID = "emp-1";

  private static final List<String> STAFF_ROLES = List.of("OWNER", "ADMIN", "MANAGER", "CASHIER");

  // ---- scenario ------------------------------------------------------------------------------

  /** A past decision on a request: its status and how many days ago it was decided. */
  record Decision(WorkerJoinStatus status, int daysAgo) {}

  /**
   * One account as the collaborators see it.
   *
   * @param role the account's role name
   * @param hasShop whether {@code UserAccount.shopId} is set
   * @param linked the linked employee's status, or null for no linked employee
   * @param linkedInOwnShop the linked employee's shop is the account's shop (else another shop)
   * @param open the open request's status (PENDING or APPROVING), or null for none
   * @param decision a decided request in the history, or null
   * @param paired the linked employee has an active pairing
   * @param punchMinutes minutes after midnight of today's punches, in any order
   */
  record Scenario(String role, boolean hasShop, EmployeeStatus linked, boolean linkedInOwnShop,
      WorkerJoinStatus open, Decision decision, boolean paired, List<Integer> punchMinutes) {

    boolean worker() {
      return "WORKER".equals(role);
    }

    String accountShop() {
      return hasShop ? OWN_SHOP : null;
    }

    String employeeShop() {
      return hasShop && linkedInOwnShop ? OWN_SHOP : OTHER_SHOP;
    }

    State expectedState() {
      if (linked == EmployeeStatus.ACTIVE) {
        return State.ACTIVE;
      }
      if (open != null) {
        return State.PENDING;
      }
      return worker() ? State.NO_SHOP : State.STAFF_NO_EMPLOYEE;
    }

    /** Whether the Join screen should get the "did not accept your request" card. */
    boolean expectsLastOutcome() {
      return expectedState() == State.NO_SHOP
          && decision != null
          && WorkerStateService.SHOWN_OUTCOMES.contains(decision.status())
          && decision.daysAgo() < 90;
    }
  }

  @Provide
  Arbitrary<Scenario> scenarios() {
    // WORKER as often as all staff roles together, so every state is reached often
    Arbitrary<String> roles = Arbitraries.frequencyOf(
        Tuple.of(1, Arbitraries.just("WORKER")),
        Tuple.of(1, Arbitraries.of(STAFF_ROLES)));
    Arbitrary<EmployeeStatus> linked = Arbitraries.of(EmployeeStatus.class).injectNull(0.34);
    Arbitrary<WorkerJoinStatus> open =
        Arbitraries.of(WorkerJoinStatus.PENDING, WorkerJoinStatus.APPROVING).injectNull(0.5);
    Arbitrary<Decision> decisions = Combinators.combine(
            Arbitraries.of(WorkerJoinStatus.REJECTED, WorkerJoinStatus.WITHDRAWN,
                WorkerJoinStatus.APPROVED),
            // the 90-day window's edges, one try in four, else any age up to half a year
            Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.of(89, 90, 91)),
                Tuple.of(3, Arbitraries.integers().between(0, 180))))
        .as(Decision::new)
        .injectNull(0.3);
    // minutes after midnight, strictly before "now" (14:42 IST); no edge cases, so that the
    // product of the eight dimensions does not use up every try on edge cases alone
    Arbitrary<List<Integer>> punches =
        Arbitraries.integers().between(0, 14 * 60 + 40).list().ofMaxSize(3).withoutEdgeCases();
    return Combinators.combine(roles, Arbitraries.of(true, false), linked,
            Arbitraries.of(true, false), open, decisions, Arbitraries.of(true, false), punches)
        .as(Scenario::new);
  }

  // ---- properties ----------------------------------------------------------------------------

  @Property(tries = 300)
  void stateFollowsTheR61Order(@ForAll("scenarios") Scenario s) {
    WorkerStateResponse r = service(s).state(USER);

    assertEquals(s.expectedState(), r.getState(), s.toString());
    assertEquals(s.role(), r.getAccount().getRole());
    assertEquals(USER, r.getAccount().getUserId());
    assertEquals(NOW, r.getServerTime());
    assertNotNull(r.getNotifications(), "the banner stack is present in every state");
  }

  @Property(tries = 300)
  void activeSectionsAndModulesArePresentIffActive(@ForAll("scenarios") Scenario s) {
    WorkerStateResponse r = service(s).state(USER);

    boolean active = s.expectedState() == State.ACTIVE;
    assertEquals(active, r.getEmployee() != null, "employee present iff ACTIVE: " + s);
    assertEquals(active, r.getShop() != null, "shop present iff ACTIVE: " + s);
    assertEquals(active, r.getPairing() != null, "pairing present iff ACTIVE: " + s);
    assertEquals(active, r.getToday() != null, "today present iff ACTIVE: " + s);
    if (active) {
      assertEquals(WorkerModules.forShop(s.employeeShop()), r.getModules());
      assertEquals(EMPLOYEE_ID, r.getEmployee().getEmployeeId());
      assertEquals(EmployeeStatus.ACTIVE, r.getEmployee().getStatus());
      assertEquals(s.employeeShop(), r.getShop().getShopId());
      assertEquals(s.paired() ? PairingStateResponse.State.PAIRED
          : PairingStateResponse.State.NOT_PAIRED, r.getPairing().getState());
      assertEquals(s.paired(), r.getPairing().getPairingId() != null);
      assertTodayMatchesThePunches(s, r.getToday());
    } else {
      assertEquals(List.of(), r.getModules(), "modules empty unless ACTIVE: " + s);
    }
  }

  @Property(tries = 300)
  void requestAndLastOutcomeFollowTheState(@ForAll("scenarios") Scenario s) {
    WorkerStateResponse r = service(s).state(USER);

    boolean pending = s.expectedState() == State.PENDING;
    assertEquals(pending, r.getRequest() != null, "request present iff PENDING: " + s);
    if (pending) {
      assertEquals(s.open(), r.getRequest().getStatus());
    }
    assertEquals(s.expectsLastOutcome(), r.getLastOutcome() != null,
        "lastOutcome only in NO_SHOP, for a rejection or withdrawal within 90 days: " + s);
    if (r.getLastOutcome() != null) {
      assertEquals(s.decision().status(), r.getLastOutcome().getStatus());
      assertTrue(WorkerStateService.SHOWN_OUTCOMES.contains(r.getLastOutcome().getStatus()));
    }
    if (r.getState() != State.NO_SHOP) {
      assertNull(r.getLastOutcome(), "no trace of a former shop outside NO_SHOP (R5.5)");
    }
  }

  /** R6.5: in = first counted punch, out = last when two or more, next IN until a punch counts. */
  private static void assertTodayMatchesThePunches(Scenario s, WorkerStateResponse.Today t) {
    assertEquals("2025-04-09", t.getDate());
    boolean anyPunch = !s.punchMinutes().isEmpty();
    assertEquals(anyPunch, t.getIn() != null, "in iff a punch today: " + s);
    assertEquals(anyPunch ? PunchDirection.OUT : PunchDirection.IN, t.getNextDirection());
    if (t.getOut() != null) {
      assertNotNull(t.getIn(), "out never without in");
      assertTrue(t.getIn().compareTo(t.getOut()) <= 0, t.getIn() + " <= " + t.getOut());
    }
    if (anyPunch) {
      int first = s.punchMinutes().stream().min(Comparator.naturalOrder()).orElseThrow();
      assertEquals(String.format("%02d:%02d", first / 60, first % 60), t.getIn());
    }
  }

  // ---- wiring --------------------------------------------------------------------------------

  /** A service over fakes that answer from the scenario the way the real collaborators would. */
  private static WorkerStateService service(Scenario s) {
    AccountView account = new AccountView(USER, "Priya Sharma", "priya@example.com",
        s.worker() ? "9876543210" : null, s.role(), s.accountShop(), s.worker());

    WorkerAccounts accounts = mock(WorkerAccounts.class);
    when(accounts.account(anyString())).thenAnswer(inv ->
        USER.equals(inv.getArgument(0)) ? Optional.of(account) : Optional.empty());
    when(accounts.hasPhoto(anyString())).thenReturn(false);

    List<Employee> employees = new ArrayList<>();
    if (s.linked() != null) {
      employees.add(employee(s));
    }
    EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
    when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenAnswer(inv -> {
      String userId = inv.getArgument(0);
      EmployeeStatus status = inv.getArgument(1);
      return employees.stream()
          .filter(e -> userId.equals(e.getUserId()) && e.getStatus() == status)
          .toList();
    });

    List<WorkerJoinRequest> requests = new ArrayList<>();
    if (s.open() != null) {
      requests.add(openRequest(s.open()));
    }
    if (s.decision() != null) {
      requests.add(decidedRequest(s.decision()));
    }
    WorkerJoinRequestRepository requestRepository = mock(WorkerJoinRequestRepository.class);
    when(requestRepository.findFirstByUserIdAndStatusIn(anyString(), any())).thenAnswer(inv -> {
      String userId = inv.getArgument(0);
      Collection<WorkerJoinStatus> statuses = inv.getArgument(1);
      return requests.stream()
          .filter(r -> userId.equals(r.getUserId()) && statuses.contains(r.getStatus()))
          .findFirst();
    });
    when(requestRepository.findFirstByUserIdAndDecidedAtAfterOrderByDecidedAtDesc(anyString(),
        any())).thenAnswer(inv -> {
          String userId = inv.getArgument(0);
          Instant since = inv.getArgument(1);
          return requests.stream()
              .filter(r -> userId.equals(r.getUserId()) && r.getDecidedAt() != null
                  && r.getDecidedAt().isAfter(since))
              .max(Comparator.comparing(WorkerJoinRequest::getDecidedAt));
        });

    DevicePairingService pairingService = mock(DevicePairingService.class);
    when(pairingService.activePairing(anyString(), anyString())).thenAnswer(inv -> {
      if (!s.paired() || !EMPLOYEE_ID.equals(inv.getArgument(1))) {
        return Optional.empty();
      }
      DevicePairing p = new DevicePairing();
      p.setId("pairing-1");
      return Optional.of(p);
    });

    PunchDayService punchDays = mock(PunchDayService.class);
    when(punchDays.punchesOf(anyString(), anyString(), anyString())).thenAnswer(inv -> {
      if (!EMPLOYEE_ID.equals(inv.getArgument(1)) || !"2025-04-09".equals(inv.getArgument(2))) {
        return List.of();
      }
      List<Punch> punches = new ArrayList<>();
      int i = 0;
      for (int minutes : s.punchMinutes()) {
        Punch p = new Punch();
        p.setId("p" + i++);
        p.setShopId(inv.getArgument(0));
        p.setEmployeeId(EMPLOYEE_ID);
        p.setLocalDate("2025-04-09");
        p.setAt(TODAY_START.plus(Duration.ofMinutes(minutes)));
        punches.add(p);
      }
      return punches;
    });

    HrNotificationService notifications = mock(HrNotificationService.class);
    when(notifications.listForAccount(anyString(), anyInt()))
        .thenReturn(new HrAccountNotificationsResponse(List.of(), 0));

    ShopNamePort shopNames = mock(ShopNamePort.class);
    when(shopNames.getShopName(anyString())).thenAnswer(inv -> "Shop " + inv.getArgument(0));

    WorkerStateService service = new WorkerStateService();
    ReflectionTestUtils.setField(service, "workerAccounts", accounts);
    ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
    ReflectionTestUtils.setField(service, "requestRepository", requestRepository);
    ReflectionTestUtils.setField(service, "pairingService", pairingService);
    ReflectionTestUtils.setField(service, "punchDays", punchDays);
    ReflectionTestUtils.setField(service, "notificationService", notifications);
    ReflectionTestUtils.setField(service, "shopNamePort", shopNames);
    service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    return service;
  }

  private static Employee employee(Scenario s) {
    Employee e = new Employee();
    e.setId(EMPLOYEE_ID);
    e.setShopId(s.employeeShop());
    e.setCode("EMP-001");
    e.setName("Priya Sharma");
    e.setUserId(USER);
    e.setJoinDate("2025-04-01");
    e.setStatus(s.linked());
    if (s.linked() == EmployeeStatus.EXITED) {
      e.setExitDate("2025-04-05");
    }
    return e;
  }

  private static WorkerJoinRequest openRequest(WorkerJoinStatus status) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("req-open");
    r.setShopId(OWN_SHOP);
    r.setUserId(USER);
    r.setStatus(status);
    r.setRequestedAt(NOW.minus(Duration.ofHours(1)));
    return r;
  }

  private static WorkerJoinRequest decidedRequest(Decision d) {
    WorkerJoinRequest r = new WorkerJoinRequest();
    r.setId("req-decided");
    r.setShopId(OTHER_SHOP);
    r.setUserId(USER);
    r.setStatus(d.status());
    r.setRequestedAt(NOW.minus(Duration.ofDays(d.daysAgo()).plusHours(1)));
    r.setDecidedAt(NOW.minus(Duration.ofDays(d.daysAgo())));
    r.setDecidedByUserId("approver");
    if (d.status() == WorkerJoinStatus.REJECTED) {
      r.setRejectionReason("Not hiring now");
    }
    return r;
  }
}
