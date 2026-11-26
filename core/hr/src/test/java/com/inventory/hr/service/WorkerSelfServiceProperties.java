// Feature: worker-app, Property 18: Self-service answers the caller's own data only
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.WorkerProfileResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 18 of the worker-app design: for any population of shops, employees (linked or not,
 * ACTIVE or EXITED), accounts (WORKER or staff) and punches, every self-service answer for a caller
 * is exactly that caller's own data. {@link WorkerSelfService#punches} returns the caller's linked
 * ACTIVE employee's punches in the asked window, newest first, and no punch of anybody else (R7.9,
 * R8.4); the only way to name an employee is the Bearer token, so no row of another employee in
 * any shop can appear, however the population looks. {@link WorkerSelfService#profile} names the
 * caller's own account and employee and its JSON carries none of {@code salaryRevisions}, {@code
 * bankAccountNumber}, {@code ifsc}, {@code upiId}, {@code paymentMethod}, an amount or an identity
 * field (R11.2), and never another employee's code. A caller with no linked ACTIVE employee gets the
 * design's refusal ({@code HR_WORKER_NOT_ACTIVE}) on history and Leave this shop and the account-only
 * profile; a staff account with no employee anywhere is {@code HR_WORKER_ONLY}; an unknown account is
 * not authenticated.
 *
 * <p>The collaborators are in-memory fakes that answer the way the Mongo queries would: the punch
 * finder filters by shop, employee and day and sorts by {@code at} descending, the employee finder
 * filters by user and status. The unique rules of {@code HrIndexMigration} (one ACTIVE employee per
 * login per shop; a WORKER has one shop) are kept by the generator.
 *
 * <p><b>Validates: Requirements 7.9, 8.4, 11.2</b>
 */
class WorkerSelfServiceProperties {

  /** 2025-04-09 14:42 IST. */
  private static final Instant NOW = Instant.parse("2025-04-09T09:12:00Z");
  private static final LocalDate TODAY = LocalDate.of(2025, 4, 9);

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final List<String> STAFF_ROLES = List.of("OWNER", "ADMIN", "MANAGER", "CASHIER");
  private static final List<String> USERS = List.of("u0", "u1", "u2", "u3");
  private static final String GHOST = "ghost";

  /** JSON keys the profile must never have (R11.2, R8.9), matched case-insensitively. */
  private static final List<String> FORBIDDEN_KEY_PARTS = List.of("salaryrevisions",
      "bankaccountnumber", "ifsc", "upiid", "paymentmethod", "amount", "salary", "aadhaar", "pan",
      "address");

  // ---- scenario ------------------------------------------------------------------------------

  /** A punch of one employee: a day relative to today and the minute of that day. */
  record PunchSpec(int daysAgo, int minuteOfDay) {}

  /**
   * One employee as generated: shop index, status, the index into {@link #USERS} of the linked
   * login (or -1), whether the phone is paired, and the punches.
   */
  record EmployeeSpec(int shop, EmployeeStatus status, int user, boolean paired,
      List<PunchSpec> punches) {

    EmployeeSpec unlinked() {
      return new EmployeeSpec(shop, status, -1, paired, punches);
    }
  }

  /** One login: WORKER or a staff role, and whether its {@code shopId} is set. */
  record AccountSpec(String role, boolean hasShop) {

    boolean worker() {
      return "WORKER".equals(role);
    }
  }

  /** The asked history window: both bounds left out, or {@code from} and a length in days. */
  record Window(boolean explicit, int fromDaysAgo, int lengthDays) {

    LocalDate from() {
      return explicit ? TODAY.minusDays(fromDaysAgo)
          : TODAY.minusDays(AppPunchService.HISTORY_MAX_DAYS - 1);
    }

    LocalDate to() {
      return explicit ? from().plusDays(lengthDays) : TODAY;
    }
  }

  record Scenario(int shops, List<EmployeeSpec> employees, List<AccountSpec> accounts,
      String caller, Window window) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<Integer> shops = Arbitraries.integers().between(1, 3);
    Arbitrary<PunchSpec> punch = Combinators.combine(
            Arbitraries.integers().between(0, 45),
            Arbitraries.integers().between(0, 24 * 60 - 1))
        .as(PunchSpec::new);
    Arbitrary<EmployeeSpec> employee = Combinators.combine(
            Arbitraries.integers().between(0, 2),
            Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just(EmployeeStatus.ACTIVE)),
                Tuple.of(1, Arbitraries.just(EmployeeStatus.EXITED))),
            // linked to u0..u3 three times in four, unlinked otherwise
            Arbitraries.integers().between(-1, USERS.size() - 1),
            Arbitraries.of(true, false),
            punch.list().ofMaxSize(4).withoutEdgeCases())
        .as(EmployeeSpec::new);
    Arbitrary<AccountSpec> account = Combinators.combine(
            Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.just("WORKER")),
                Tuple.of(1, Arbitraries.of(STAFF_ROLES))),
            Arbitraries.of(true, false))
        .as(AccountSpec::new);
    Arbitrary<String> caller = Arbitraries.frequencyOf(
        Tuple.of(12, Arbitraries.of(USERS)),
        Tuple.of(1, Arbitraries.just(GHOST)));
    Arbitrary<Window> window = Combinators.combine(
            Arbitraries.of(true, false),
            Arbitraries.integers().between(0, 50),
            Arbitraries.integers().between(0, AppPunchService.HISTORY_MAX_DAYS - 1))
        .as(Window::new);
    // the product of these dimensions is wide; no edge cases on the lists (see Property 13)
    return Combinators.combine(shops, employee.list().ofMinSize(1).ofMaxSize(6).withoutEdgeCases(),
            account.list().ofSize(USERS.size()).withoutEdgeCases(), caller, window)
        .as(Scenario::new)
        .map(WorkerSelfServiceProperties::keepTheUniqueRules);
  }

  /**
   * Shops beyond {@code shops} fold into shop 0; a login keeps at most one ACTIVE employee per shop
   * ({@code shop_user_unique}) and a WORKER at most one ACTIVE employee anywhere (R5.1). Later
   * employees that would break a rule are unlinked.
   */
  private static Scenario keepTheUniqueRules(Scenario s) {
    Map<Integer, Set<Integer>> activeShopsByUser = new HashMap<>();
    List<EmployeeSpec> out = new ArrayList<>();
    for (EmployeeSpec e : s.employees()) {
      EmployeeSpec fixed = e.shop() < s.shops() ? e
          : new EmployeeSpec(0, e.status(), e.user(), e.paired(), e.punches());
      if (fixed.user() >= 0 && fixed.status() == EmployeeStatus.ACTIVE) {
        Set<Integer> shopsOfUser =
            activeShopsByUser.computeIfAbsent(fixed.user(), x -> new HashSet<>());
        boolean worker = s.accounts().get(fixed.user()).worker();
        if (shopsOfUser.contains(fixed.shop()) || (worker && !shopsOfUser.isEmpty())) {
          fixed = fixed.unlinked();
        } else {
          shopsOfUser.add(fixed.shop());
        }
      }
      out.add(fixed);
    }
    return new Scenario(s.shops(), out, s.accounts(), s.caller(), s.window());
  }

  // ---- the model -----------------------------------------------------------------------------

  /** The population built from a scenario, with the answers the service should give. */
  static final class World {
    final Scenario scenario;
    final Map<String, AccountView> accounts = new LinkedHashMap<>();
    final List<Employee> employees = new ArrayList<>();
    final Map<String, List<Punch>> punchesByEmployee = new LinkedHashMap<>();
    final Set<String> pairedEmployees = new HashSet<>();

    World(Scenario s) {
      this.scenario = s;
      for (int i = 0; i < s.accounts().size(); i++) {
        AccountSpec a = s.accounts().get(i);
        String userId = USERS.get(i);
        accounts.put(userId, new AccountView(userId, "Name " + userId, userId + "@example.com",
            a.worker() ? "98765432" + String.format("%02d", i) : null, a.role(),
            accountShop(s, i), a.worker()));
      }
      for (int i = 0; i < s.employees().size(); i++) {
        EmployeeSpec spec = s.employees().get(i);
        Employee e = new Employee();
        e.setId("emp-" + i);
        e.setShopId(shopId(spec.shop()));
        e.setCode("CODE-S" + spec.shop() + "-E" + i);
        e.setName("Employee " + i);
        e.setPhone("90000000" + String.format("%02d", i));
        e.setEmail("e" + i + "@example.com");
        e.setUserId(spec.user() >= 0 ? USERS.get(spec.user()) : null);
        e.setJoinDate("2025-01-0" + (1 + i % 9));
        e.setStatus(spec.status());
        if (spec.status() == EmployeeStatus.EXITED) {
          e.setExitDate("2025-04-01");
        }
        e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision("2025-01",
            PayType.MONTHLY, new BigDecimal("12345.67"), Instant.EPOCH, "owner"))));
        e.setPaymentMethod(HrPaymentMethod.BANK);
        e.setBankAccountNumber("ACCT" + i + "9988776655");
        e.setIfsc("IFSC000" + i + "XYZ");
        e.setUpiId("upi" + i + "@okbank");
        employees.add(e);
        if (spec.paired()) {
          pairedEmployees.add(e.getId());
        }
        List<Punch> punches = new ArrayList<>();
        int n = 0;
        for (PunchSpec ps : spec.punches()) {
          LocalDate day = TODAY.minusDays(ps.daysAgo());
          Punch p = new Punch();
          p.setId(e.getId() + "-p" + n++);
          p.setShopId(e.getShopId());
          p.setEmployeeId(e.getId());
          p.setLocalDate(HrDateUtils.format(day));
          p.setAt(day.atStartOfDay(HrDateUtils.ZONE).toInstant()
              .plus(Duration.ofMinutes(ps.minuteOfDay())));
          punches.add(p);
        }
        punchesByEmployee.put(e.getId(), punches);
      }
    }

    /** A WORKER's shop is its ACTIVE employee's shop (DD1); a staff login's is any shop or none. */
    private static String accountShop(Scenario s, int user) {
      AccountSpec a = s.accounts().get(user);
      if (!a.hasShop()) {
        return null;
      }
      if (a.worker()) {
        return s.employees().stream()
            .filter(e -> e.user() == user && e.status() == EmployeeStatus.ACTIVE)
            .map(e -> shopId(e.shop()))
            .findFirst()
            .orElse(null);
      }
      return shopId(user % s.shops());
    }

    static String shopId(int shop) {
      return "shop-" + shop;
    }

    List<Employee> activeLinkedTo(String userId) {
      return employees.stream()
          .filter(e -> userId.equals(e.getUserId()) && e.getStatus() == EmployeeStatus.ACTIVE)
          .toList();
    }

    /** The R5.1 choice rule of {@link WorkerPairingService#linkedActiveEmployee}. */
    Optional<Employee> expectedOwnEmployee(String userId) {
      List<Employee> linked = activeLinkedTo(userId);
      if (linked.isEmpty()) {
        return Optional.empty();
      }
      if (linked.size() == 1) {
        return Optional.of(linked.get(0));
      }
      String activeShop = accounts.get(userId).shopId();
      return linked.stream()
          .filter(e -> activeShop != null && activeShop.equals(e.getShopId()))
          .findFirst()
          .or(() -> linked.stream().min(Comparator.comparing(Employee::getShopId)));
    }

    /** The caller's own punches whose day lies in the window, newest first. */
    List<Punch> expectedPunches(Employee own, Window w) {
      String from = HrDateUtils.format(w.from());
      String to = HrDateUtils.format(w.to());
      return punchesByEmployee.get(own.getId()).stream()
          .filter(p -> p.getLocalDate().compareTo(from) >= 0 && p.getLocalDate().compareTo(to) <= 0)
          .sorted(Comparator.comparing(Punch::getAt).reversed())
          .toList();
    }

    Set<String> allPunchIds() {
      Set<String> ids = new HashSet<>();
      punchesByEmployee.values().forEach(ps -> ps.forEach(p -> ids.add(p.getId())));
      return ids;
    }
  }

  // ---- properties ----------------------------------------------------------------------------

  @Property(tries = 300)
  void historyIsExactlyTheCallersOwnPunchesInTheWindowNewestFirst(
      @ForAll("scenarios") Scenario s) {
    World w = new World(s);
    Harness h = new Harness(w);
    Optional<Employee> own = w.expectedOwnEmployee(s.caller());

    if (own.isEmpty()) {
      // no linked ACTIVE employee (a worker without a shop, a staff login, an unknown account):
      // the design's refusal, and no punch of anybody is read
      BaseException e = assertThrows(BaseException.class, () -> h.service.punches(s.caller(),
          s.window().explicit() ? HrDateUtils.format(s.window().from()) : null,
          s.window().explicit() ? HrDateUtils.format(s.window().to()) : null), s.toString());
      assertEquals(ErrorCode.HR_WORKER_NOT_ACTIVE, e.getErrorCode(), s.toString());
      verify(h.punchRepository, never()).findRangeNewestFirst(any(), any(), any(), any());
      return;
    }

    List<PunchResponse> rows = h.service.punches(s.caller(),
        s.window().explicit() ? HrDateUtils.format(s.window().from()) : null,
        s.window().explicit() ? HrDateUtils.format(s.window().to()) : null);

    Employee mine = own.get();
    List<Punch> expected = w.expectedPunches(mine, s.window());
    assertEquals(expected.stream().map(Punch::getId).toList(),
        rows.stream().map(PunchResponse::getId).toList(),
        "exactly the own punches in the window, newest first: " + s);
    Set<String> others = w.allPunchIds();
    w.punchesByEmployee.get(mine.getId()).forEach(p -> others.remove(p.getId()));
    for (PunchResponse r : rows) {
      assertEquals(mine.getId(), r.getEmployeeId(), "every row is the caller's employee: " + s);
      assertFalse(others.contains(r.getId()), "no row of another employee: " + s);
      assertTrue(r.getLocalDate().compareTo(HrDateUtils.format(s.window().from())) >= 0
          && r.getLocalDate().compareTo(HrDateUtils.format(s.window().to())) <= 0,
          "every row is inside the window: " + s);
    }
    for (int i = 1; i < rows.size(); i++) {
      assertTrue(rows.get(i - 1).getAt().compareTo(rows.get(i).getAt()) >= 0, "newest first: " + s);
    }
    // the query itself is scoped to the own employee and shop (R8.4): one call, with those values
    verify(h.punchRepository).findRangeNewestFirst(mine.getShopId(), mine.getId(),
        HrDateUtils.format(s.window().from()), HrDateUtils.format(s.window().to()));
  }

  @Property(tries = 300)
  void profileIsTheCallersOwnAccountAndEmployeeWithoutPayOrIdentityFields(
      @ForAll("scenarios") Scenario s) throws JsonProcessingException {
    World w = new World(s);
    Harness h = new Harness(w);

    if (!w.accounts.containsKey(s.caller())) {
      assertThrows(AuthenticationException.class, () -> h.service.profile(s.caller()));
      return;
    }

    WorkerProfileResponse p = h.service.profile(s.caller());

    AccountView account = w.accounts.get(s.caller());
    assertEquals(account.name(), p.getName(), s.toString());
    assertEquals(account.phone(), p.getPhone(), s.toString());
    assertEquals(account.email(), p.getEmail(), s.toString());
    Optional<Employee> own = w.expectedOwnEmployee(s.caller());
    if (own.isPresent()) {
      Employee mine = own.get();
      assertEquals(mine.getCode(), p.getEmployeeCode(), s.toString());
      assertEquals("Shop " + mine.getShopId(), p.getShopName(), s.toString());
      assertEquals(mine.getJoinDate(), p.getJoinDate(), s.toString());
      assertEquals(w.pairedEmployees.contains(mine.getId())
          ? PairingStateResponse.State.PAIRED : PairingStateResponse.State.NOT_PAIRED,
          p.getPairingState(), s.toString());
    } else {
      assertNull(p.getEmployeeCode(), "no employee: no code: " + s);
      assertNull(p.getShopName(), "no employee: no shop: " + s);
      assertNull(p.getJoinDate(), "no employee: no join date: " + s);
      assertFalse(p.isProfileRequested(), s.toString());
      assertEquals(PairingStateResponse.State.NOT_PAIRED, p.getPairingState(), s.toString());
    }

    String json = JSON.writeValueAsString(p);
    JsonNode tree = JSON.readTree(json);
    for (Iterator<String> keys = tree.fieldNames(); keys.hasNext(); ) {
      String key = keys.next().toLowerCase(Locale.ROOT);
      for (String part : FORBIDDEN_KEY_PARTS) {
        assertFalse(key.contains(part), "profile JSON has a pay or identity key " + key + ": " + s);
      }
    }
    for (Employee e : w.employees) {
      boolean mineRow = own.isPresent() && own.get().getId().equals(e.getId());
      if (!mineRow) {
        assertFalse(json.contains(e.getCode()), "another employee's code in the profile: " + s);
        assertFalse(json.contains(e.getName()), "another employee's name in the profile: " + s);
        assertFalse(json.contains(e.getPhone()), "another employee's phone in the profile: " + s);
      }
      // pay and payment values never travel, the caller's own included (R11.2)
      assertFalse(json.contains(e.getBankAccountNumber()), "bank account in the profile: " + s);
      assertFalse(json.contains(e.getIfsc()), "IFSC in the profile: " + s);
      assertFalse(json.contains(e.getUpiId()), "UPI id in the profile: " + s);
      assertFalse(json.contains("12345.67"), "an amount in the profile: " + s);
    }
  }

  @Property(tries = 300)
  void leaveAndAccessFollowTheCallersOwnLink(@ForAll("scenarios") Scenario s) {
    World w = new World(s);
    Harness h = new Harness(w);

    if (!w.accounts.containsKey(s.caller())) {
      // an unknown account: not authenticated where the account is read (the controller's first
      // call), and nobody is exited; the unknown token itself is the interceptor's to refuse
      assertThrows(AuthenticationException.class, () -> h.service.requireWorkerAccess(s.caller()));
      BaseException e = assertThrows(BaseException.class, () -> h.service.leave(s.caller()));
      assertEquals(ErrorCode.HR_WORKER_NOT_ACTIVE, e.getErrorCode(), s.toString());
      verify(h.employeeService, never()).exitBySelf(any(), any());
      return;
    }

    Optional<Employee> own = w.expectedOwnEmployee(s.caller());
    if (own.isPresent()) {
      h.service.leave(s.caller());
      // Leave this shop reaches exactly the caller's own employee, in its own shop (R5.3, R8.4)
      verify(h.employeeService).exitBySelf(own.get().getShopId(), s.caller());
      verify(h.stateService).state(s.caller());
    } else {
      BaseException e = assertThrows(BaseException.class, () -> h.service.leave(s.caller()));
      assertEquals(ErrorCode.HR_WORKER_NOT_ACTIVE, e.getErrorCode(), s.toString());
      verify(h.employeeService, never()).exitBySelf(any(), any());
    }

    // who may do what (R5.6): a WORKER passes; a staff login passes iff linked and ACTIVE somewhere
    AccountView account = w.accounts.get(s.caller());
    if (account.worker() || !w.activeLinkedTo(s.caller()).isEmpty()) {
      assertSame(account, h.service.requireWorkerAccess(s.caller()), s.toString());
    } else {
      BaseException e = assertThrows(BaseException.class,
          () -> h.service.requireWorkerAccess(s.caller()));
      assertEquals(ErrorCode.HR_WORKER_ONLY, e.getErrorCode(), s.toString());
    }
    assertNotNull(h.service.notifications(s.caller()), "notifications answer for any account");
  }

  // ---- wiring --------------------------------------------------------------------------------

  /** A service over fakes that answer from the world the way the real collaborators would. */
  static final class Harness {
    final WorkerSelfService service = new WorkerSelfService();
    final PunchRepository punchRepository = mock(PunchRepository.class);
    final EmployeeService employeeService = mock(EmployeeService.class);
    final WorkerStateService stateService = mock(WorkerStateService.class);

    Harness(World w) {
      WorkerAccounts accounts = mock(WorkerAccounts.class);
      when(accounts.account(anyString())).thenAnswer(inv ->
          Optional.ofNullable(w.accounts.get((String) inv.getArgument(0))));
      when(accounts.hasPhoto(anyString())).thenReturn(false);

      EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
      when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenAnswer(inv -> {
        String userId = inv.getArgument(0);
        EmployeeStatus status = inv.getArgument(1);
        return w.employees.stream()
            .filter(e -> userId.equals(e.getUserId()) && e.getStatus() == status)
            .toList();
      });

      when(punchRepository.findRangeNewestFirst(anyString(), anyString(), anyString(), anyString()))
          .thenAnswer(inv -> {
            String shopId = inv.getArgument(0);
            String employeeId = inv.getArgument(1);
            String from = inv.getArgument(2);
            String to = inv.getArgument(3);
            return w.punchesByEmployee.values().stream()
                .flatMap(List::stream)
                .filter(p -> shopId.equals(p.getShopId()) && employeeId.equals(p.getEmployeeId())
                    && p.getLocalDate().compareTo(from) >= 0 && p.getLocalDate().compareTo(to) <= 0)
                .sorted(Comparator.comparing(Punch::getAt).reversed())
                .toList();
          });

      DevicePairingService pairingService = mock(DevicePairingService.class);
      when(pairingService.activePairing(anyString(), anyString())).thenAnswer(inv -> {
        String employeeId = inv.getArgument(1);
        if (!w.pairedEmployees.contains(employeeId)) {
          return Optional.empty();
        }
        DevicePairing p = new DevicePairing();
        p.setId("pairing-" + employeeId);
        return Optional.of(p);
      });

      WorkerPairingService workerPairingService = new WorkerPairingService();
      ReflectionTestUtils.setField(workerPairingService, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(workerPairingService, "workerAccounts", accounts);
      ReflectionTestUtils.setField(workerPairingService, "pairingService", pairingService);

      HrNotificationService notifications = mock(HrNotificationService.class);
      when(notifications.listForAccount(anyString(), anyInt()))
          .thenReturn(new HrAccountNotificationsResponse(List.of(), 0));

      ShopNamePort shopNames = mock(ShopNamePort.class);
      when(shopNames.getShopName(anyString())).thenAnswer(inv -> "Shop " + inv.getArgument(0));

      ReflectionTestUtils.setField(service, "workerAccounts", accounts);
      ReflectionTestUtils.setField(service, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(service, "punchRepository", punchRepository);
      ReflectionTestUtils.setField(service, "employeeService", employeeService);
      ReflectionTestUtils.setField(service, "pairingService", pairingService);
      ReflectionTestUtils.setField(service, "workerPairingService", workerPairingService);
      ReflectionTestUtils.setField(service, "stateService", stateService);
      ReflectionTestUtils.setField(service, "notificationService", notifications);
      ReflectionTestUtils.setField(service, "shopNamePort", shopNames);
      service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
    }
  }
}
