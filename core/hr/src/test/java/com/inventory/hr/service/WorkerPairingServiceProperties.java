// Feature: worker-app, Property 17: A pairing code pairs only its own worker
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.service.DevicePairingServiceProperties.MutableClock;
import com.inventory.hr.service.DevicePairingServiceProperties.Store;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Property 17 of the worker-app design (task 6.6), run against the real {@link
 * WorkerPairingService} → {@link DevicePairingService} chain over the in-memory stand-ins of {@link
 * DevicePairingServiceProperties.Store} (codes, pairings) plus an equally small fake for employees.
 * The clock is a {@link MutableClock}, so the code's fifteen minutes are decided by the test, not
 * the wall. Property 27 (the pairing-link classification) was removed with the legacy pairing
 * flows (D11).
 *
 * <p><b>Property 17 — Validates: Requirements 7.3, 7.4, 7.5, 9.9.</b> For any shop picture
 * (four employees, each linked to at most one of three accounts or to none), any pairing code
 * (manager-created or self-created, for any of the employees) and any sequence of signed-in callers
 * before the claim and before the completion: on the Bearer path {@code claim} and {@code complete}
 * succeed only for the account whose linked ACTIVE employee is the code's employee; every other
 * account is refused with {@code HR_PAIRING_WRONG_EMPLOYEE}, which counts one attempt on the code,
 * writes exactly one {@code PAIRING_CODE_REFUSED} audit row on the code's employee naming the
 * caller, and leaves the code's status and expiry untouched, so the right worker still pairs within
 * the same fifteen minutes. An account with no linked ACTIVE employee is turned away before the code
 * is read ({@code HR_WORKER_NOT_ACTIVE}: nothing counted, nothing written). There is no other path
 * that redeems a code (R7.5): {@code DevicePairingService} refuses a handshake without a caller
 * before any code is read, and the unsigned device route no longer exists ({@code
 * AttendanceAppControllerTest}, {@code WorkerControllerIT}). The last clause of the property, that
 * punch acceptance never depends on the link, is Property 26 (task 6.8).
 */
class WorkerPairingServiceProperties {

  private static final Instant T0 = Instant.parse("2025-04-03T09:12:00Z");

  private static final String SHOP = "shop-1";
  private static final String OTHER_SHOP = "shop-2";
  /** emp-0..emp-2 work in {@link #SHOP}; emp-3 in {@link #OTHER_SHOP}. */
  private static final List<String> EMPLOYEE_IDS = List.of("emp-0", "emp-1", "emp-2", "emp-3");
  /** The three accounts an employee may be linked to; index 3 below is an account linked nowhere. */
  private static final List<String> USERS = List.of("user-a", "user-b", "user-c", "user-none");
  private static final int NOBODY = 3;
  private static final String MANAGER = "user-manager";
  private static final String INSTALL = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
  private static final List<String> KEYS = keys(2);

  // ---- Property 17 ---------------------------------------------------------------------------

  /**
   * One shop picture and one code.
   *
   * @param linkOf per employee (index into {@link #EMPLOYEE_IDS}), the linked account (index into
   *     {@link #USERS}) or −1 for no login; every account appears at most once
   * @param codeEmp the employee the code is for
   * @param selfCode created from My HR ({@code createdBy = self}) rather than by the manager
   * @param tickBefore minutes between the code's creation and the first claim
   * @param preClaim accounts that try {@code claim} before the right one
   * @param tickMid minutes between those attempts and the right claim
   * @param preComplete accounts that try {@code complete} with the right phone's claim token before
   *     the right one does
   */
  record BearerScenario(List<Integer> linkOf, int codeEmp, boolean selfCode, int tickBefore,
      List<Integer> preClaim, int tickMid, List<Integer> preComplete) {

    /** The account the code is meant for, or −1 when its employee has no login. */
    int rightUser() {
      return linkOf.get(codeEmp);
    }

    /** The employee the account is linked to, or −1. */
    int linkedEmployee(int user) {
      return linkOf.indexOf(user);
    }

    Verdict verdict(int user) {
      int mine = linkedEmployee(user);
      if (mine < 0) {
        return Verdict.NOT_ACTIVE;
      }
      return mine == codeEmp ? Verdict.OK : Verdict.WRONG_EMPLOYEE;
    }
  }

  enum Verdict { OK, WRONG_EMPLOYEE, NOT_ACTIVE }

  @Provide
  Arbitrary<BearerScenario> bearerScenarios() {
    // each of the three accounts linked to at most one employee; two "no login" slots
    Arbitrary<List<Integer>> linkOf = Arbitraries.shuffle(0, 1, 2, -1, -1)
        .map(l -> List.copyOf(l.subList(0, EMPLOYEE_IDS.size())));
    Arbitrary<Integer> codeEmp = Arbitraries.integers().between(0, EMPLOYEE_IDS.size() - 1);
    Arbitrary<Boolean> selfCode = Arbitraries.of(true, false);
    // both ticks together stay inside the code's 15 minutes; no edge cases on the lists and
    // ticks, so the product of seven dimensions does not spend every try on them
    Arbitrary<Integer> tick = Arbitraries.integers().between(0, 7).withoutEdgeCases();
    Arbitrary<List<Integer>> callers = Arbitraries.integers().between(0, USERS.size() - 1)
        .list().ofMaxSize(3).withoutEdgeCases();
    return Combinators.combine(linkOf, codeEmp, selfCode, tick, callers, tick, callers)
        .as(BearerScenario::new);
  }

  @Property(tries = 200)
  void aPairingCodePairsOnlyItsOwnWorker(@ForAll("bearerScenarios") BearerScenario s) {
    World w = new World();
    w.seedEmployees(s.linkOf());
    String codeEmployee = EMPLOYEE_IDS.get(s.codeEmp());
    String codeShop = w.employee(codeEmployee).getShopId();
    String code = w.device.createCode(codeShop, codeEmployee,
        s.selfCode() ? DevicePairingService.SELF : MANAGER).getCode();
    PairingCode fresh = w.theCode();
    Instant expiresAt = fresh.getExpiresAt();
    w.audits.clear();
    w.clock.advance(Duration.ofMinutes(s.tickBefore()));

    // wrong and login-less callers before anyone claims: the code stays UNUSED
    int attempts = 0;
    for (int user : s.preClaim()) {
      if (s.verdict(user) == Verdict.OK) {
        continue;
      }
      attempts += w.assertRefusedClaim(s, user, code, attempts, PairingCodeStatus.UNUSED);
    }
    w.clock.advance(Duration.ofMinutes(s.tickMid()));

    if (s.rightUser() < 0) {
      // nobody may pair with it: it is still the manager's fresh code
      PairingCode left = w.theCode();
      assertEquals(PairingCodeStatus.UNUSED, left.getStatus(), s.toString());
      assertEquals(expiresAt, left.getExpiresAt());
      assertEquals(attempts, left.getAttempts());
      assertTrue(w.device.activePairedEmployeeIds(codeShop).isEmpty());
      return;
    }

    // the right worker claims, inside the same fifteen minutes
    String right = USERS.get(s.rightUser());
    w.audits.clear();
    ClaimPairingResponse claimed = w.worker.claim(right, claimBody(code));
    attempts++;
    assertEquals(w.employee(codeEmployee).getName(), claimed.getEmployeeName(),
        "the names are the caller's own (R7.2): " + s);
    assertEquals("Shop " + codeShop, claimed.getShopName());
    assertTrue(w.audits.isEmpty(), "a successful claim writes no refusal row: " + s);
    PairingCode afterClaim = w.theCode();
    assertEquals(PairingCodeStatus.CLAIMED, afterClaim.getStatus());
    assertEquals(attempts, afterClaim.getAttempts());
    assertEquals(expiresAt, afterClaim.getExpiresAt(), "refusals never change the expiry");

    // other accounts on the same phone try to finish with the right claim token
    for (int user : s.preComplete()) {
      if (s.verdict(user) == Verdict.OK) {
        continue;
      }
      attempts += w.assertRefusedComplete(s, user, claimed.getClaimToken(), attempts);
    }

    w.audits.clear();
    PairingBootstrapResponse paired = w.worker.complete(right, completeBody(claimed.getClaimToken()));
    assertEquals(codeEmployee, paired.getEmployeeId(), s.toString());
    assertEquals(codeShop, paired.getShopId());
    assertEquals(List.of(HrAuditAction.DEVICE_PAIRED),
        w.audits.stream().map(AuditEntry::action).toList(), s.toString());
    assertTrue(w.store.codes.isEmpty(), "a completed code is deleted: " + s);
    assertEquals(Optional.of(paired.getPairingId()),
        w.device.activePairing(codeShop, codeEmployee).map(DevicePairing::getId));
    for (String emp : EMPLOYEE_IDS) {
      if (!emp.equals(codeEmployee)) {
        assertTrue(w.device.activePairing(w.employee(emp).getShopId(), emp).isEmpty(),
            "nobody else got paired: " + s);
      }
    }
  }

  /**
   * R7.5, the "no other path" clause: whatever the code, a handshake step that names no caller is
   * refused by the service before any code is read, counts nothing and writes nothing. (The removed
   * device route itself is a 404, checked where the controllers are.)
   */
  @Property(tries = 50)
  void aHandshakeWithoutACallerTouchesNoCode(@ForAll("bearerScenarios") BearerScenario s) {
    World w = new World();
    w.seedEmployees(s.linkOf());
    String codeEmployee = EMPLOYEE_IDS.get(s.codeEmp());
    String codeShop = w.employee(codeEmployee).getShopId();
    String code = w.device.createCode(codeShop, codeEmployee,
        s.selfCode() ? DevicePairingService.SELF : MANAGER).getCode();
    PairingCode fresh = w.theCode();
    w.audits.clear();

    assertThrows(IllegalArgumentException.class, () -> w.device.claim(code, INSTALL,
        DeviceOs.ANDROID, "13", "Redmi Note 11", "1.4.0", null));
    assertThrows(IllegalArgumentException.class, () -> w.device.complete("any-token", INSTALL,
        KEYS.get(0), KEYS.get(1), 1, null, null));

    PairingCode after = w.theCode();
    assertEquals(PairingCodeStatus.UNUSED, after.getStatus(), s.toString());
    assertEquals(fresh.getAttempts(), after.getAttempts(), "nothing counted: " + s);
    assertTrue(w.audits.isEmpty(), "nothing written: " + s);
    assertTrue(w.device.activePairedEmployeeIds(codeShop).isEmpty());
  }

  // ---- the world: real services over in-memory stores -----------------------------------------

  static final class World {
    final Store store = new Store();
    final MutableClock clock = new MutableClock(T0);
    final List<AuditEntry> audits = new ArrayList<>();
    final Map<String, Employee> employees = new LinkedHashMap<>();
    final Map<String, AccountView> accounts = new LinkedHashMap<>();
    final DevicePairingService device;
    final WorkerPairingService worker;

    World() {
      EmployeeRepository employeeRepository = mock(EmployeeRepository.class);
      when(employeeRepository.findByShopIdAndId(anyString(), anyString())).thenAnswer(i ->
          Optional.ofNullable(employees.get(i.getArgument(0) + "/" + i.getArgument(1))));
      when(employeeRepository.findByUserIdAndStatus(anyString(), any())).thenAnswer(i ->
          employees.values().stream()
              .filter(e -> i.getArgument(0).equals(e.getUserId()) && e.getStatus() == i.getArgument(1))
              .toList());

      HrSettingsService settings = mock(HrSettingsService.class);
      HrSettings hrSettings = new HrSettings();
      hrSettings.setAttendanceCapture(new HrSettings.AttendanceCapture(false, true, false, null,
          null, null, OutsideAction.FLAG));
      when(settings.effectiveSettings(anyString())).thenReturn(hrSettings);

      HrAuditService audit = mock(HrAuditService.class);
      doAnswer(i -> {
        audits.add(i.getArgument(0));
        return null;
      }).when(audit).record(any(AuditEntry.class));

      ShopNamePort shopNames = mock(ShopNamePort.class);
      when(shopNames.getShopName(anyString())).thenAnswer(i -> "Shop " + i.getArgument(0));

      WorkerAccounts workerAccounts = mock(WorkerAccounts.class);
      when(workerAccounts.account(anyString()))
          .thenAnswer(i -> Optional.ofNullable(accounts.get(i.<String>getArgument(0))));

      device = new DevicePairingService();
      ReflectionTestUtils.setField(device, "codeRepository", store.codeRepository());
      ReflectionTestUtils.setField(device, "pairingRepository", store.pairingRepository());
      ReflectionTestUtils.setField(device, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(device, "settingsService", settings);
      ReflectionTestUtils.setField(device, "consentVersions", new ConsentVersions());
      ReflectionTestUtils.setField(device, "auditService", audit);
      ReflectionTestUtils.setField(device, "metrics", new MetricsWrapper(new SimpleMeterRegistry()));
      ReflectionTestUtils.setField(device, "mongoTemplate", store.mongoTemplate());
      ReflectionTestUtils.setField(device, "shopNamePort", shopNames);
      device.setClock(clock);

      worker = new WorkerPairingService();
      ReflectionTestUtils.setField(worker, "pairingService", device);
      ReflectionTestUtils.setField(worker, "employeeRepository", employeeRepository);
      ReflectionTestUtils.setField(worker, "workerAccounts", workerAccounts);
    }

    Employee seedEmployee(String id, String shopId, String userId) {
      Employee e = new Employee();
      e.setId(id);
      e.setShopId(shopId);
      e.setCode("EMP-" + id);
      e.setName("Worker " + id);
      e.setJoinDate("2024-04-01");
      e.setStatus(EmployeeStatus.ACTIVE);
      e.setUserId(userId);
      employees.put(shopId + "/" + id, e);
      if (userId != null) {
        account(userId);
      }
      return e;
    }

    /** The four employees of {@link #EMPLOYEE_IDS}, linked as {@code linkOf} says. */
    void seedEmployees(List<Integer> linkOf) {
      for (int i = 0; i < EMPLOYEE_IDS.size(); i++) {
        int user = linkOf.get(i);
        seedEmployee(EMPLOYEE_IDS.get(i), i == 3 ? OTHER_SHOP : SHOP,
            user < 0 ? null : USERS.get(user));
      }
      account(USERS.get(NOBODY));
    }

    /** A WORKER account with no shop of its own yet. */
    void account(String userId) {
      accounts.putIfAbsent(userId, new AccountView(userId, "Priya Sharma",
          userId + "@example.com", "9876543210", "WORKER", null, true));
    }

    Employee employee(String id) {
      return employees.values().stream().filter(e -> e.getId().equals(id)).findFirst().orElseThrow();
    }

    /** The one code of the scenario, as stored now. */
    PairingCode theCode() {
      assertEquals(1, store.codes.size(), "exactly one code exists");
      return store.codes.values().iterator().next();
    }

    /** One refused Bearer claim; returns how many attempts it counted on the code (0 or 1). */
    int assertRefusedClaim(BearerScenario s, int user, String code, int attemptsBefore,
        PairingCodeStatus statusBefore) {
      audits.clear();
      String userId = USERS.get(user);
      Verdict v = s.verdict(user);
      assertCode(refusalOf(v), () -> worker.claim(userId, claimBody(code)));
      return assertRefusal(s, userId, v, attemptsBefore, statusBefore,
          "claim by " + userId + " in " + s);
    }

    /** One refused Bearer complete with the right phone's token; returns the attempts counted. */
    int assertRefusedComplete(BearerScenario s, int user, String claimToken, int attemptsBefore) {
      audits.clear();
      String userId = USERS.get(user);
      Verdict v = s.verdict(user);
      assertCode(refusalOf(v), () -> worker.complete(userId, completeBody(claimToken)));
      return assertRefusal(s, userId, v, attemptsBefore, PairingCodeStatus.CLAIMED,
          "complete by " + userId + " in " + s);
    }

    private static ErrorCode refusalOf(Verdict v) {
      return v == Verdict.NOT_ACTIVE
          ? ErrorCode.HR_WORKER_NOT_ACTIVE : ErrorCode.HR_PAIRING_WRONG_EMPLOYEE;
    }

    private int assertRefusal(BearerScenario s, String userId, Verdict v, int attemptsBefore,
        PairingCodeStatus statusBefore, String what) {
      PairingCode c = theCode();
      assertEquals(statusBefore, c.getStatus(), "the code is left as it was: " + what);
      String codeEmployee = EMPLOYEE_IDS.get(s.codeEmp());
      assertTrue(device.activePairing(employee(codeEmployee).getShopId(), codeEmployee).isEmpty(),
          "a refusal pairs nothing: " + what);
      if (v == Verdict.NOT_ACTIVE) {
        assertEquals(attemptsBefore, c.getAttempts(), "no code is read for a login-less caller: " + what);
        assertTrue(audits.isEmpty(), "nothing written for a login-less caller: " + what);
        return 0;
      }
      assertEquals(attemptsBefore + 1, c.getAttempts(), "one attempt counted: " + what);
      assertEquals(1, audits.size(), "exactly one refusal row: " + what);
      AuditEntry a = audits.get(0);
      assertEquals(HrAuditAction.PAIRING_CODE_REFUSED, a.action());
      assertEquals(c.getShopId(), a.shopId());
      assertEquals(codeEmployee, a.employeeId(), "on the code's employee: " + what);
      assertEquals(userId, a.actorUserId(), "naming the caller: " + what);
      assertEquals(userId, a.after().get("attemptedByUserId"));
      assertEquals(c.getId(), a.entityId());
      return 1;
    }
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private static ClaimPairingRequest claimBody(String code) {
    ClaimPairingRequest body = new ClaimPairingRequest();
    body.setCode(code);
    body.setInstallationId(INSTALL);
    body.setOs(DeviceOs.ANDROID);
    body.setOsVersion("14");
    body.setPhoneModel("Redmi Note 11");
    body.setAppVersion("2.0.0");
    return body;
  }

  private static CompletePairingRequest completeBody(String claimToken) {
    CompletePairingRequest body = new CompletePairingRequest();
    body.setClaimToken(claimToken);
    body.setInstallationId(INSTALL);
    body.setPunchPublicKey(KEYS.get(0));
    body.setDevicePublicKey(KEYS.get(1));
    body.setConsentVersion(1);
    return body;
  }

  private static void assertCode(ErrorCode expected, Runnable call) {
    BaseException e = assertThrows(BaseException.class, call::run);
    assertEquals(expected, e.getErrorCode(), e.getMessage());
  }

  private static List<String> keys(int n) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      List<String> out = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        out.add(Base64.getEncoder().encodeToString(g.generateKeyPair().getPublic().getEncoded()));
      }
      return out;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
