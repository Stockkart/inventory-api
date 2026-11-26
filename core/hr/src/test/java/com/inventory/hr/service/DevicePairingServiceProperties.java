// Feature: HR-module, Property 9: Pairing state machine invariants
// Feature: HR-module, Property 11: Scheduled unpair follows the exit date
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.BaseException;
import com.inventory.hr.HrTestFixtures;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PairingCode;
import com.inventory.hr.domain.model.enums.DeviceOs;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingCodeStatus;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PairingCodeRepository;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PairingCodes;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.result.UpdateResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.RandomDistribution;
import org.bson.Document;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Properties 9 and 11 of the HR-module design (tasks 3.5, 3.6), run against the real {@link
 * DevicePairingService} and {@link PairingExitJob} over in-memory stand-ins for the two Mongo
 * collections: the Spring Data repositories and the handful of {@code MongoTemplate} operations
 * the service uses ({@code findAndModify}, {@code updateFirst}) are answered from the same two
 * maps, with the unique partial indexes of {@code HrIndexMigration} enforced on insert.
 *
 * <p><b>Property 9 — Validates: Requirements 1.9, 2.4, 2.5, 2.10, 2.11, 2.12, 2.17.</b> For any
 * sequence of {@code createCode}, {@code claim}, {@code complete}, {@code abandon}, {@code
 * unpair} (manager, worker, system) and clock ticks over a few employees and installations, a
 * simple model predicts whether each call succeeds and what the stores hold afterwards: at most
 * one {@code ACTIVE} pairing per employee and per installation, at most one {@code UNUSED} code
 * per employee, a code claimable by one installation only, completed codes deleted, displaced
 * pairings {@code UNPAIRED} with {@code REPLACED} / {@code PAIRED_TO_ANOTHER_EMPLOYEE}, {@code
 * activePairedEmployeeIds} equal to the model's set, exactly the expected audit rows per step and
 * never a public key or installation id inside one.
 *
 * <p><b>Property 11 — Validates: Requirements 3.4, 3.5, 3.6.</b> For any "now" and any sequence
 * of exit dates (or a cleared exit) an active pairing is scheduled for {@code exitDate} 00:00
 * Asia/Kolkata while the date is after today, unpaired at once as {@code EMPLOYEE_EXITED} by
 * {@code system} once it is today or earlier, and {@link PairingExitJob#unpairDue} unpairs exactly
 * the active pairings whose schedule is due and is idempotent.
 */
class DevicePairingServiceProperties {

  private static final Instant T0 = Instant.parse("2025-04-03T09:12:00Z");
  private static final ZoneId IST = HrDateUtils.ZONE;
  private static final int EMPLOYEES = 4;
  private static final int INSTALLATIONS = 4;
  private static final List<String> EMPLOYEE_IDS = List.of("emp-0", "emp-1", "emp-2", "emp-3");
  private static final List<String> INSTALLATION_IDS = List.of(
      "7c9e6679-7425-40de-944b-e07fc1f90ae7", "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
      "6ba7b810-9dad-11d1-80b4-00c04fd430c8", "f47ac10b-58cc-4372-a567-0e02b2c3d479");
  /** A pool of P-256 keys; the privacy check looks for every one of them in the audit rows. */
  private static final List<String> KEYS = keys(6);

  // ---- Property 9 ----------------------------------------------------------------------------

  enum Kind { CREATE_CODE, CLAIM, COMPLETE, ABANDON, UNPAIR, TICK, PAIR, FLOOD }

  /**
   * One step. {@code emp} and {@code install} pick from the pools; {@code pick} chooses the latest
   * (0) or an older code of the employee for a claim; {@code minutes} is the clock tick; {@code
   * reason} is {@code MANUAL} (manager), {@code WORKER} (self) or {@code EMPLOYEE_EXITED}
   * (system, through {@code scheduleExitUnpair} with today's date). {@code PAIR} and {@code
   * FLOOD} are macros: a whole handshake, and eleven claims of one code by one phone (R9.10);
   * they expand to atomic steps before running.
   */
  record Step(Kind kind, int emp, int install, int pick, int minutes, UnpairReason reason) {

    List<Step> expand() {
      return switch (kind) {
        case PAIR -> List.of(
            new Step(Kind.CREATE_CODE, emp, install, 0, minutes, reason),
            new Step(Kind.CLAIM, emp, install, 0, minutes, reason),
            new Step(Kind.COMPLETE, emp, install, 0, minutes, reason));
        case FLOOD -> Collections.nCopies(DevicePairingService.MAX_CODE_ATTEMPTS + 1,
            new Step(Kind.CLAIM, emp, install, 0, minutes, reason));
        default -> List.of(this);
      };
    }
  }

  @Property(tries = 100)
  void pairingStateMachineInvariantsHoldThroughAnySequence(@ForAll("steps") List<Step> script) {
    Harness h = new Harness();
    Model model = new Model();
    List<Step> steps = script.stream().flatMap(s -> s.expand().stream()).toList();
    for (Step step : steps) {
      h.audits.clear();
      Map<String, PairingStatus> pairingsBefore = h.store.pairingStatuses();
      Expected expected = model.predict(step, h.now());
      boolean succeeded = h.apply(step, model);
      assertEquals(expected.succeeds(), succeeded, step + " outcome");
      if (!succeeded) {
        assertEquals(pairingsBefore, h.store.pairingStatuses(), step + " refused but changed a pairing");
      }
      model.commit(step, expected, h.now(), succeeded);
      assertInvariants(h, model, expected, step);
    }
  }

  /** What the model expects of one step. */
  record Expected(boolean succeeds, List<HrAuditAction> audits, List<String> reasons,
      String replacedEmployee, String movedEmployee) {}

  private static void assertInvariants(Harness h, Model model, Expected expected, Step step) {
    Store s = h.store;
    // at most one ACTIVE pairing per employee and per installation
    Map<String, Long> activePerEmployee = s.pairings.values().stream()
        .filter(p -> p.getStatus() == PairingStatus.ACTIVE)
        .collect(Collectors.groupingBy(p -> p.getShopId() + "/" + p.getEmployeeId(),
            Collectors.counting()));
    activePerEmployee.forEach((k, n) -> assertTrue(n <= 1, step + ": " + n + " active pairings for " + k));
    Map<String, Long> activePerInstall = s.pairings.values().stream()
        .filter(p -> p.getStatus() == PairingStatus.ACTIVE)
        .collect(Collectors.groupingBy(DevicePairing::getInstallationId, Collectors.counting()));
    activePerInstall.forEach((k, n) -> assertTrue(n <= 1, step + ": " + n + " active pairings on installation " + k));

    // at most one UNUSED code per employee; completed codes are deleted
    Map<String, Long> unusedPerEmployee = s.codes.values().stream()
        .filter(c -> c.getStatus() == PairingCodeStatus.UNUSED)
        .collect(Collectors.groupingBy(PairingCode::getEmployeeId, Collectors.counting()));
    unusedPerEmployee.forEach((k, n) -> assertTrue(n <= 1, step + ": " + n + " unused codes for " + k));
    assertTrue(s.codes.values().stream().noneMatch(c -> c.getStatus() == PairingCodeStatus.USED),
        step + ": a used code is still stored");

    // the stored active pairings are exactly the model's
    assertEquals(model.active.keySet(), h.service.activePairedEmployeeIds(SHOP), step + " paired ids");
    for (String emp : EMPLOYEE_IDS) {
      Optional<DevicePairing> stored = h.service.activePairing(SHOP, emp);
      ModelPairing mp = model.active.get(emp);
      assertEquals(mp != null, stored.isPresent(), step + " active pairing of " + emp);
      PairingStateResponse state = h.service.pairingState(SHOP, emp);
      assertEquals(mp != null ? PairingStateResponse.State.PAIRED : PairingStateResponse.State.NOT_PAIRED,
          state.getState(), step + " pairing state of " + emp);
      if (mp != null) {
        DevicePairing p = stored.get();
        assertEquals(mp.installationId, p.getInstallationId(), step + " installation of " + emp);
        assertEquals(mp.punchKey, p.getPunchPublicKey());
        assertEquals(mp.deviceKey, p.getDevicePublicKey());
        assertNull(p.getUnpairedAt());
        assertNull(p.getPurgeAt());
        assertEquals(mp.pairedAt, p.getPairedAt());
        assertEquals(mp.pairedAt, p.getConsentAt());
        assertEquals(List.of(new DevicePairing.Consent(1, mp.pairedAt)), p.getConsents());
      }
    }
    // every unpaired row carries the unpair fields and its 90-day purge
    for (DevicePairing p : s.pairings.values()) {
      if (p.getStatus() == PairingStatus.UNPAIRED) {
        assertNotNull(p.getUnpairedAt(), step + ": unpaired without a time");
        assertNotNull(p.getUnpairedBy(), step + ": unpaired without an actor");
        assertNotNull(p.getUnpairReason(), step + ": unpaired without a reason");
        assertEquals(p.getUnpairedAt().plus(Duration.ofDays(90)), p.getPurgeAt());
        assertNull(p.getScheduledUnpairAt(), step + ": schedule left on an unpaired row");
      }
    }
    // the pairings displaced by this step
    if (expected.replacedEmployee() != null) {
      DevicePairing old = s.pairings.get(model.lastDisplaced.get(expected.replacedEmployee()));
      assertEquals(PairingStatus.UNPAIRED, old.getStatus());
      assertEquals(UnpairReason.REPLACED, old.getUnpairReason());
    }
    if (expected.movedEmployee() != null) {
      DevicePairing old = s.pairings.get(model.lastDisplaced.get(expected.movedEmployee()));
      assertEquals(PairingStatus.UNPAIRED, old.getStatus());
      assertEquals(UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE, old.getUnpairReason());
    }

    // exactly the expected audit rows, in order, with the right reason
    assertEquals(expected.audits(), h.audits.stream().map(AuditEntry::action).toList(),
        step + " audit actions");
    assertEquals(expected.reasons(), h.audits.stream().map(AuditEntry::reason).toList(),
        step + " audit reasons");
    for (AuditEntry a : h.audits) {
      assertEquals(SHOP, a.shopId());
      assertNotNull(a.employeeId(), step + ": audit row without an employee");
      String text = String.valueOf(a.before()) + String.valueOf(a.after()) + String.valueOf(a.reason())
          + String.valueOf(a.entityId());
      for (String key : KEYS) {
        assertFalse(text.contains(key), step + ": audit row carries a public key");
      }
      for (String install : INSTALLATION_IDS) {
        assertFalse(text.contains(install), step + ": audit row carries an installation id");
      }
      for (ModelCode c : model.codes.values()) {
        assertFalse(text.contains(c.code), step + ": audit row carries a pairing code");
      }
    }
  }

  @Provide
  Arbitrary<List<Step>> steps() {
    Arbitrary<Integer> emp = Arbitraries.integers().between(0, EMPLOYEES - 1);
    Arbitrary<Integer> install = Arbitraries.integers().between(0, INSTALLATIONS - 1);
    Arbitrary<Integer> pick = Arbitraries.frequency(
        net.jqwik.api.Tuple.of(6, 0), net.jqwik.api.Tuple.of(2, 1), net.jqwik.api.Tuple.of(1, 2));
    Arbitrary<Integer> minutes = Arbitraries.integers().between(1, 16);
    Arbitrary<UnpairReason> reason = Arbitraries.of(UnpairReason.MANUAL, UnpairReason.WORKER,
        UnpairReason.EMPLOYEE_EXITED);
    Arbitrary<Kind> kind = Arbitraries.frequency(
        net.jqwik.api.Tuple.of(4, Kind.CREATE_CODE), net.jqwik.api.Tuple.of(4, Kind.CLAIM),
        net.jqwik.api.Tuple.of(4, Kind.COMPLETE), net.jqwik.api.Tuple.of(2, Kind.ABANDON),
        net.jqwik.api.Tuple.of(3, Kind.UNPAIR), net.jqwik.api.Tuple.of(2, Kind.TICK),
        net.jqwik.api.Tuple.of(5, Kind.PAIR), net.jqwik.api.Tuple.of(1, Kind.FLOOD));
    return Combinators.combine(kind, emp, install, pick, minutes, reason).as(Step::new)
        .list().ofMinSize(1).ofMaxSize(30)
        .withSizeDistribution(RandomDistribution.uniform());
  }

  // ---- Property 11 ---------------------------------------------------------------------------

  @Property(tries = 100)
  void scheduledUnpairFollowsTheExitDate(@ForAll("now") Instant now,
      @ForAll("exitChanges") List<Integer> offsets) {
    Harness h = new Harness(now);
    LocalDate today = now.atZone(IST).toLocalDate();
    String emp = EMPLOYEE_IDS.get(0);
    String pairingId = h.store.seedActive(emp, INSTALLATION_IDS.get(0), T0.minus(Duration.ofDays(1)));

    boolean unpaired = false;
    for (Integer offset : offsets) {
      h.audits.clear();
      boolean clear = offset == null;
      LocalDate exitDate = clear ? null : today.plusDays(offset);
      Optional<DevicePairing> result = clear
          ? clearAndReturnEmpty(h, emp)
          : h.service.scheduleExitUnpair(SHOP, emp, exitDate);
      DevicePairing p = h.store.pairings.get(pairingId);

      if (unpaired) {
        // nothing left to schedule or unpair: the call is a no-op
        assertTrue(result.isEmpty(), "already unpaired: " + exitDate);
        assertEquals(PairingStatus.UNPAIRED, p.getStatus());
        assertNull(p.getScheduledUnpairAt());
        assertTrue(h.audits.isEmpty(), "no audit row for a no-op");
      } else if (clear) {
        assertTrue(result.isEmpty());
        assertEquals(PairingStatus.ACTIVE, p.getStatus());
        assertNull(p.getScheduledUnpairAt(), "cleared exit removes the schedule");
        assertTrue(h.audits.isEmpty());
      } else if (exitDate.isAfter(today)) {
        assertTrue(result.isEmpty(), "future exit is only scheduled");
        assertEquals(PairingStatus.ACTIVE, p.getStatus());
        assertEquals(exitDate.atStartOfDay(IST).toInstant(), p.getScheduledUnpairAt(),
            "00:00 Asia/Kolkata on the exit date");
        assertTrue(p.getScheduledUnpairAt().isAfter(now));
        assertTrue(h.audits.isEmpty());
      } else {
        assertTrue(result.isPresent(), "exit today or earlier unpairs at once");
        assertEquals(PairingStatus.UNPAIRED, p.getStatus());
        assertEquals(UnpairReason.EMPLOYEE_EXITED, p.getUnpairReason());
        assertEquals(DevicePairingService.SYSTEM, p.getUnpairedBy());
        assertEquals(now, p.getUnpairedAt());
        assertNull(p.getScheduledUnpairAt());
        assertEquals(1, h.audits.size());
        AuditEntry a = h.audits.get(0);
        assertEquals(HrAuditAction.DEVICE_UNPAIRED, a.action());
        assertEquals(DevicePairingService.SYSTEM, a.actorUserId());
        assertEquals(UnpairReason.EMPLOYEE_EXITED.name(), a.reason());
        assertEquals(emp, a.employeeId());
        unpaired = true;
      }
      assertEquals(unpaired ? Set.of() : Set.of(emp), h.service.activePairedEmployeeIds(SHOP));
    }
  }

  private static Optional<DevicePairing> clearAndReturnEmpty(Harness h, String emp) {
    h.service.clearExitUnpair(SHOP, emp);
    return Optional.empty();
  }

  /** A pairing seeded for the job: {@code dueOffsetSeconds} null = no schedule. */
  record Scheduled(Long dueOffsetSeconds, boolean active) {}

  @Property(tries = 100)
  void exitJobUnpairsExactlyTheDuePairingsAndIsIdempotent(@ForAll("now") Instant now,
      @ForAll("scheduled") List<Scheduled> seeds) {
    Harness h = new Harness(now);
    PairingExitJob job = new PairingExitJob();
    ReflectionTestUtils.setField(job, "pairingRepository", h.pairingRepository);
    ReflectionTestUtils.setField(job, "pairingService", h.service);
    job.setClock(h.clock);

    Set<String> due = new LinkedHashSet<>();
    Map<String, Scheduled> byId = new LinkedHashMap<>();
    for (int i = 0; i < seeds.size(); i++) {
      Scheduled s = seeds.get(i);
      String emp = "emp-" + i;
      String id = h.store.seedActive(emp, "install-" + i, T0.minus(Duration.ofDays(2)));
      DevicePairing p = h.store.pairings.get(id);
      if (s.dueOffsetSeconds() != null) {
        p.setScheduledUnpairAt(now.plusSeconds(s.dueOffsetSeconds()));
      }
      if (!s.active()) {
        p.setStatus(PairingStatus.UNPAIRED);
        p.setUnpairedAt(now.minusSeconds(60));
        p.setUnpairedBy(USER);
        p.setUnpairReason(UnpairReason.MANUAL);
        p.setPurgeAt(p.getUnpairedAt().plus(Duration.ofDays(90)));
        p.setScheduledUnpairAt(null);
      }
      byId.put(id, s);
      if (s.active() && s.dueOffsetSeconds() != null && s.dueOffsetSeconds() <= 0) {
        due.add(id);
      }
    }

    int done = job.unpairDue(now);

    assertEquals(due.size(), done, "unpaired exactly the due pairings");
    assertEquals(due.size(), h.audits.size(), "one audit row per unpaired pairing");
    for (AuditEntry a : h.audits) {
      assertEquals(HrAuditAction.DEVICE_UNPAIRED, a.action());
      assertEquals(DevicePairingService.SYSTEM, a.actorUserId());
      assertEquals(UnpairReason.EMPLOYEE_EXITED.name(), a.reason());
      assertTrue(due.contains(a.entityId()), "audit row for a pairing that was not due");
    }
    Map<String, PairingStatus> after = h.store.pairingStatuses();
    for (Map.Entry<String, Scheduled> e : byId.entrySet()) {
      DevicePairing p = h.store.pairings.get(e.getKey());
      if (due.contains(e.getKey())) {
        assertEquals(PairingStatus.UNPAIRED, p.getStatus());
        assertEquals(UnpairReason.EMPLOYEE_EXITED, p.getUnpairReason());
        assertEquals(DevicePairingService.SYSTEM, p.getUnpairedBy());
        assertEquals(now, p.getUnpairedAt());
        assertNull(p.getScheduledUnpairAt());
      } else if (e.getValue().active()) {
        assertEquals(PairingStatus.ACTIVE, p.getStatus(), "a pairing not yet due stays active");
        Long off = e.getValue().dueOffsetSeconds();
        assertEquals(off == null ? null : now.plusSeconds(off), p.getScheduledUnpairAt(),
            "a future schedule is kept");
      } else {
        assertEquals(UnpairReason.MANUAL, p.getUnpairReason(), "an unpaired row is left alone");
      }
    }

    // idempotent: a second tick finds nothing and changes nothing
    h.audits.clear();
    assertEquals(0, job.unpairDue(now));
    assertTrue(h.audits.isEmpty());
    assertEquals(after, h.store.pairingStatuses());
  }

  @Provide
  Arbitrary<Instant> now() {
    return Arbitraries.longs()
        .between(Instant.parse("2024-01-01T00:00:00Z").getEpochSecond(),
            Instant.parse("2027-12-31T23:59:59Z").getEpochSecond())
        .map(Instant::ofEpochSecond);
  }

  /** Day offsets from today; {@code null} = the exit was cancelled. */
  @Provide
  Arbitrary<List<Integer>> exitChanges() {
    return Arbitraries.integers().between(-400, 400).injectNull(0.15).list()
        .ofMinSize(1).ofMaxSize(5);
  }

  @Provide
  Arbitrary<List<Scheduled>> scheduled() {
    Arbitrary<Long> offset = Arbitraries.frequencyOf(
        net.jqwik.api.Tuple.of(4, Arbitraries.longs().between(-30L * 86_400, -1)),
        net.jqwik.api.Tuple.of(1, Arbitraries.just(0L)),
        net.jqwik.api.Tuple.of(4, Arbitraries.longs().between(1, 30L * 86_400)));
    Arbitrary<Boolean> active = Arbitraries.frequency(
        net.jqwik.api.Tuple.of(3, Boolean.TRUE), net.jqwik.api.Tuple.of(1, Boolean.FALSE));
    return Combinators.combine(offset.injectNull(0.2), active)
        .as(Scheduled::new).list().ofMinSize(0).ofMaxSize(8);
  }

  // ---- the model for Property 9 --------------------------------------------------------------

  static final class ModelCode {
    final String code;
    final String employeeId;
    final Instant expiresAt;
    PairingCodeStatus status = PairingCodeStatus.UNUSED;
    int attempts;
    String claimedBy;
    String token;

    ModelCode(String code, String employeeId, Instant expiresAt) {
      this.code = code;
      this.employeeId = employeeId;
      this.expiresAt = expiresAt;
    }

    boolean live() {
      return status == PairingCodeStatus.UNUSED || status == PairingCodeStatus.CLAIMED;
    }
  }

  record ModelPairing(String pairingId, String installationId, String punchKey, String deviceKey,
      Instant pairedAt) {}

  static final class Model {
    /** Every code ever created, by plaintext (deleted ones removed). */
    final Map<String, ModelCode> codes = new LinkedHashMap<>();
    /** Codes per employee, oldest first, plaintext; keeps deleted ones for stale claims. */
    final Map<String, List<String>> codesOf = new HashMap<>();
    /** The token each installation last received. */
    final Map<String, String> tokenOf = new HashMap<>();
    /** Active pairings by employee. */
    final Map<String, ModelPairing> active = new LinkedHashMap<>();
    /** Pairing id the employee's last displacement or unpair ended. */
    final Map<String, String> lastDisplaced = new HashMap<>();
    private ModelPairing pending;

    private ModelCode codeFor(Step s) {
      List<String> list = codesOf.getOrDefault(EMPLOYEE_IDS.get(s.emp()), List.of());
      if (list.isEmpty() || s.pick() >= list.size()) {
        return null;
      }
      return codes.get(list.get(list.size() - 1 - s.pick()));
    }

    private ModelCode codeByToken(String token) {
      if (token == null) {
        return null;
      }
      return codes.values().stream().filter(c -> token.equals(c.token)).findFirst().orElse(null);
    }

    /** The service counts an attempt first; the 11th on a live code invalidates it. */
    private boolean countAttemptInvalidates(ModelCode c) {
      c.attempts++;
      if (c.attempts > DevicePairingService.MAX_CODE_ATTEMPTS && c.live()) {
        c.status = PairingCodeStatus.INVALIDATED;
        return true;
      }
      return false;
    }

    private static final List<HrAuditAction> NONE = List.of();

    Expected predict(Step s, Instant now) {
      String emp = EMPLOYEE_IDS.get(s.emp());
      String install = INSTALLATION_IDS.get(s.install());
      switch (s.kind()) {
        case TICK -> {
          return new Expected(true, NONE, List.of(), null, null);
        }
        case CREATE_CODE -> {
          return new Expected(true, List.of(HrAuditAction.PAIRING_CODE_CREATED),
              Collections.singletonList(null), null, null);
        }
        case CLAIM -> {
          ModelCode c = codeFor(s);
          if (c == null) {
            return refused();
          }
          if (countAttemptInvalidates(c)) {
            return refused();
          }
          if (!now.isBefore(c.expiresAt)) {
            return refused();
          }
          boolean ok = switch (c.status) {
            case UNUSED -> true;
            case CLAIMED -> install.equals(c.claimedBy);
            default -> false;
          };
          return new Expected(ok, NONE, List.of(), null, null);
        }
        case COMPLETE -> {
          ModelCode c = codeByToken(tokenOf.get(install));
          if (c == null) {
            return refused();
          }
          if (countAttemptInvalidates(c)) {
            return refused();
          }
          if (!now.isBefore(c.expiresAt) || c.status != PairingCodeStatus.CLAIMED
              || !install.equals(c.claimedBy)) {
            return refused();
          }
          String owner = c.employeeId;
          ModelPairing replaced = active.get(owner);
          String movedEmployee = active.entrySet().stream()
              .filter(e -> !e.getKey().equals(owner) && e.getValue().installationId().equals(install))
              .map(Map.Entry::getKey).findFirst().orElse(null);
          List<HrAuditAction> audits = new ArrayList<>();
          List<String> reasons = new ArrayList<>();
          if (movedEmployee != null) {
            audits.add(HrAuditAction.DEVICE_UNPAIRED);
            reasons.add(UnpairReason.PAIRED_TO_ANOTHER_EMPLOYEE.name());
          }
          if (replaced != null) {
            audits.add(HrAuditAction.DEVICE_REPLACED);
            reasons.add(UnpairReason.REPLACED.name());
          } else {
            audits.add(HrAuditAction.DEVICE_PAIRED);
            reasons.add(null);
          }
          return new Expected(true, audits, reasons, replaced != null ? owner : null, movedEmployee);
        }
        case ABANDON -> {
          return new Expected(true, NONE, List.of(), null, null);
        }
        case UNPAIR -> {
          boolean had = active.containsKey(emp);
          return new Expected(true,
              had ? List.of(HrAuditAction.DEVICE_UNPAIRED) : NONE,
              had ? List.of(s.reason().name()) : List.of(), null, null);
        }
        default -> throw new IllegalStateException();
      }
    }

    private static Expected refused() {
      return new Expected(false, NONE, List.of(), null, null);
    }

    /** Applies the step's effect to the model; the harness hands over what the service returned. */
    void commit(Step s, Expected e, Instant now, boolean succeeded) {
      String emp = EMPLOYEE_IDS.get(s.emp());
      String install = INSTALLATION_IDS.get(s.install());
      switch (s.kind()) {
        case CREATE_CODE -> {
          // the previous UNUSED codes were cancelled when the harness registered the new one
        }
        case CLAIM -> {
          if (succeeded) {
            ModelCode c = codeFor(s);
            c.status = PairingCodeStatus.CLAIMED;
            c.claimedBy = install;
            // token filled in by the harness (claimed)
          }
        }
        case COMPLETE -> {
          if (succeeded) {
            ModelCode c = codeByToken(tokenOf.get(install));
            String owner = c.employeeId;
            if (e.movedEmployee() != null) {
              lastDisplaced.put(e.movedEmployee(), active.remove(e.movedEmployee()).pairingId());
            }
            if (e.replacedEmployee() != null) {
              lastDisplaced.put(owner, active.remove(owner).pairingId());
            }
            codes.remove(c.code);
            active.put(owner, Objects.requireNonNull(pending, "harness did not hand over the pairing"));
            pending = null;
          }
        }
        case ABANDON -> {
          ModelCode c = codeByToken(tokenOf.get(install));
          if (c != null && c.live()) {
            c.status = PairingCodeStatus.INVALIDATED;
          }
        }
        case UNPAIR -> {
          ModelPairing p = active.remove(emp);
          if (p != null) {
            lastDisplaced.put(emp, p.pairingId());
          }
        }
        case TICK -> { }
        default -> throw new IllegalStateException();
      }
    }

    /** A fresh code cancels the employee's UNUSED ones (R2.4). */
    void newCode(String emp, String code, Instant expiresAt) {
      for (String old : codesOf.getOrDefault(emp, List.of())) {
        ModelCode c = codes.get(old);
        if (c != null && c.status == PairingCodeStatus.UNUSED) {
          c.status = PairingCodeStatus.CANCELLED;
        }
      }
      codes.put(code, new ModelCode(code, emp, expiresAt));
      codesOf.computeIfAbsent(emp, k -> new ArrayList<>()).add(code);
    }

    void claimed(ModelCode c, String install, String token) {
      c.token = token;
      tokenOf.put(install, token);
    }

    /** The pairing the service just created; {@code commit} activates it after the displacement. */
    void paired(ModelPairing p) {
      pending = p;
    }
  }

  // ---- harness: the service over in-memory stores ---------------------------------------------

  /** A settable clock the service and the job read. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  static final class Harness {
    final Store store = new Store();
    final MutableClock clock;
    final List<AuditEntry> audits = new ArrayList<>();
    final DevicePairingRepository pairingRepository;
    final PairingCodeRepository codeRepository;
    final DevicePairingService service;

    Harness() {
      this(T0);
    }

    Harness(Instant now) {
      clock = new MutableClock(now);
      pairingRepository = store.pairingRepository();
      codeRepository = store.codeRepository();

      EmployeeRepository employees = mock(EmployeeRepository.class);
      when(employees.findByShopIdAndId(anyString(), anyString()))
          .thenAnswer(i -> Optional.of(HrTestFixtures.employee(i.getArgument(1))));
      HrSettingsService settings = mock(HrSettingsService.class);
      HrSettings hrSettings = new HrSettings();
      hrSettings.setAttendanceCapture(new HrSettings.AttendanceCapture(false, true, true, 12.97,
          77.59, 150, OutsideAction.FLAG));
      when(settings.effectiveSettings(anyString())).thenReturn(hrSettings);
      HrAuditService audit = mock(HrAuditService.class);
      doAnswer(i -> {
        audits.add(i.getArgument(0));
        return null;
      }).when(audit).record(any(AuditEntry.class));

      service = new DevicePairingService();
      ReflectionTestUtils.setField(service, "codeRepository", codeRepository);
      ReflectionTestUtils.setField(service, "pairingRepository", pairingRepository);
      ReflectionTestUtils.setField(service, "employeeRepository", employees);
      ReflectionTestUtils.setField(service, "settingsService", settings);
      ReflectionTestUtils.setField(service, "consentVersions", new ConsentVersions());
      ReflectionTestUtils.setField(service, "auditService", audit);
      ReflectionTestUtils.setField(service, "metrics", new MetricsWrapper(new SimpleMeterRegistry()));
      ReflectionTestUtils.setField(service, "mongoTemplate", store.mongoTemplate());
      ReflectionTestUtils.setField(service, "shopNamePort", mock(ShopNamePort.class));
      service.setClock(clock);
    }

    Instant now() {
      return clock.instant();
    }

    /** The employee a code names, as the employee repository above answers it. */
    private static Employee employeeOf(String employeeId) {
      return HrTestFixtures.employee(employeeId);
    }

    /** Runs the step; true when the service accepted it. Feeds returned values to the model. */
    boolean apply(Step s, Model model) {
      String emp = EMPLOYEE_IDS.get(s.emp());
      String install = INSTALLATION_IDS.get(s.install());
      try {
        switch (s.kind()) {
          case TICK -> clock.advance(Duration.ofMinutes(s.minutes()));
          case CREATE_CODE -> {
            PairingCodeResponse r = service.createCode(SHOP, emp, USER);
            assertEquals(now().plus(DevicePairingService.CODE_VALIDITY), r.getExpiresAt());
            model.newCode(emp, r.getCode(), r.getExpiresAt());
          }
          case CLAIM -> {
            ModelCode c = model.codeFor(s);
            String code = c != null ? c.code : "ZZZZZZZ2";
            // the caller is the code's own employee: the HR-module rules alone are under test
            // here; who may redeem which code is WorkerPairingServiceProperties' (Property 17)
            String token = service.claim(code, install, DeviceOs.ANDROID, "13",
                "Phone " + s.install(), "1.0.0", employeeOf(emp)).getClaimToken();
            model.claimed(c, install, token);
          }
          case COMPLETE -> {
            String token = model.tokenOf.getOrDefault(install, "no-token");
            String punchKey = KEYS.get((s.emp() + s.install()) % KEYS.size());
            String deviceKey = KEYS.get((s.emp() + 2 * s.install() + 1) % KEYS.size());
            // the caller is the claimed code's own employee (the phone that claimed it is that
            // worker's), so again only the HR-module rules decide; a token nobody holds reads as
            // expired whichever employee asks
            ModelCode claimed = model.codeByToken(token);
            String caller = claimed != null ? claimed.employeeId : emp;
            String pairingId = service.complete(token, install, punchKey, deviceKey, 1, null,
                employeeOf(caller)).getPairingId();
            model.paired(new ModelPairing(pairingId, install, punchKey, deviceKey, now()));
          }
          case ABANDON -> service.abandon(model.tokenOf.get(install));
          case UNPAIR -> {
            if (s.reason() == UnpairReason.EMPLOYEE_EXITED) {
              service.scheduleExitUnpair(SHOP, emp, now().atZone(IST).toLocalDate());
            } else {
              service.unpairActive(SHOP, emp, s.reason(),
                  s.reason() == UnpairReason.WORKER ? DevicePairingService.SELF : USER);
            }
          }
          default -> throw new IllegalStateException();
        }
        return true;
      } catch (BaseException e) {
        return false;
      }
    }
  }

  /**
   * The two collections. Reads and writes copy, like a database would, so a document mutated after
   * a read is not stored until {@code save}. Insert enforces the unique indexes of {@code
   * HrIndexMigration}.
   */
  static final class Store {
    final Map<String, DevicePairing> pairings = new LinkedHashMap<>();
    final Map<String, PairingCode> codes = new LinkedHashMap<>();
    private int ids;

    Map<String, PairingStatus> pairingStatuses() {
      Map<String, PairingStatus> out = new LinkedHashMap<>();
      pairings.forEach((id, p) -> out.put(id, p.getStatus()));
      return out;
    }

    String seedActive(String emp, String install, Instant pairedAt) {
      DevicePairing p = new DevicePairing();
      p.setShopId(SHOP);
      p.setEmployeeId(emp);
      p.setStatus(PairingStatus.ACTIVE);
      p.setInstallationId(install);
      p.setPunchPublicKey(KEYS.get(0));
      p.setDevicePublicKey(KEYS.get(1));
      p.setOs(DeviceOs.ANDROID);
      p.setPhoneModel("Redmi Note 11");
      p.setPairedAt(pairedAt);
      p.setConsentAt(pairedAt);
      p.setConsentVersion(1);
      p.setConsents(new ArrayList<>(List.of(new DevicePairing.Consent(1, pairedAt))));
      p.setPairedBy(USER);
      p.setCreatedAt(pairedAt);
      return insertPairing(p).getId();
    }

    // -- pairings

    private DevicePairing insertPairing(DevicePairing p) {
      if (p.getId() == null) {
        p.setId("pairing-" + (++ids));
      }
      if (pairings.containsKey(p.getId())) {
        throw new DuplicateKeyException("_id");
      }
      if (p.getStatus() == PairingStatus.ACTIVE) {
        for (DevicePairing other : pairings.values()) {
          if (other.getStatus() != PairingStatus.ACTIVE) {
            continue;
          }
          if (Objects.equals(other.getShopId(), p.getShopId())
              && Objects.equals(other.getEmployeeId(), p.getEmployeeId())) {
            throw new DuplicateKeyException("shopId_employeeId_active");
          }
          if (Objects.equals(other.getInstallationId(), p.getInstallationId())) {
            throw new DuplicateKeyException("installationId_active");
          }
        }
      }
      pairings.put(p.getId(), copy(p));
      return copy(p);
    }

    private List<DevicePairing> findPairings(Predicate<DevicePairing> where) {
      return pairings.values().stream().filter(where).map(Store::copy).toList();
    }

    DevicePairingRepository pairingRepository() {
      DevicePairingRepository repo = mock(DevicePairingRepository.class);
      when(repo.insert(any(DevicePairing.class)))
          .thenAnswer(i -> insertPairing(i.getArgument(0)));
      when(repo.save(any(DevicePairing.class))).thenAnswer(i -> {
        DevicePairing p = i.getArgument(0);
        if (p.getId() == null) {
          return insertPairing(p);
        }
        pairings.put(p.getId(), copy(p));
        return copy(p);
      });
      when(repo.findById(anyString()))
          .thenAnswer(i -> Optional.ofNullable(pairings.get(i.<String>getArgument(0))).map(Store::copy));
      when(repo.findByIdAndStatus(anyString(), any())).thenAnswer(i -> findPairings(
          p -> p.getId().equals(i.getArgument(0)) && p.getStatus() == i.getArgument(1))
          .stream().findFirst());
      when(repo.findByShopIdAndEmployeeIdAndStatus(anyString(), anyString(), any()))
          .thenAnswer(i -> {
            List<DevicePairing> hits = findPairings(p -> p.getShopId().equals(i.getArgument(0))
                && p.getEmployeeId().equals(i.getArgument(1)) && p.getStatus() == i.getArgument(2));
            if (hits.size() > 1 && i.getArgument(2) == PairingStatus.ACTIVE) {
              fail("two active pairings for " + i.getArgument(1));
            }
            return hits.stream().findFirst();
          });
      when(repo.findByInstallationIdAndStatus(anyString(), any())).thenAnswer(i -> findPairings(
          p -> i.getArgument(0).equals(p.getInstallationId()) && p.getStatus() == i.getArgument(1))
          .stream().findFirst());
      when(repo.findByShopIdAndEmployeeIdInAndStatus(anyString(), any(), any())).thenAnswer(i -> {
        Collection<String> ids = i.getArgument(1);
        return findPairings(p -> p.getShopId().equals(i.getArgument(0))
            && ids.contains(p.getEmployeeId()) && p.getStatus() == i.getArgument(2));
      });
      when(repo.findByStatusAndScheduledUnpairAtLessThanEqual(any(), any())).thenAnswer(i -> {
        Instant now = i.getArgument(1);
        return findPairings(p -> p.getStatus() == i.getArgument(0)
            && p.getScheduledUnpairAt() != null && !p.getScheduledUnpairAt().isAfter(now));
      });
      when(repo.findByShopIdAndStatus(anyString(), any())).thenAnswer(i -> findPairings(
          p -> p.getShopId().equals(i.getArgument(0)) && p.getStatus() == i.getArgument(1)));
      return repo;
    }

    // -- codes

    private PairingCode insertCode(PairingCode c) {
      if (c.getId() == null) {
        c.setId("code-" + (++ids));
      }
      if (codes.containsKey(c.getId())) {
        throw new DuplicateKeyException("_id");
      }
      if (codes.values().stream().anyMatch(o -> Objects.equals(o.getCodeHash(), c.getCodeHash()))) {
        throw new DuplicateKeyException("codeHash");
      }
      codes.put(c.getId(), copy(c));
      return copy(c);
    }

    private PairingCode saveCode(PairingCode c) {
      if (c.getId() == null) {
        return insertCode(c);
      }
      codes.put(c.getId(), copy(c));
      return copy(c);
    }

    private List<PairingCode> findCodes(Predicate<PairingCode> where) {
      return codes.values().stream().filter(where).map(Store::copy).toList();
    }

    PairingCodeRepository codeRepository() {
      PairingCodeRepository repo = mock(PairingCodeRepository.class);
      when(repo.insert(any(PairingCode.class))).thenAnswer(i -> insertCode(i.getArgument(0)));
      when(repo.save(any(PairingCode.class))).thenAnswer(i -> saveCode(i.getArgument(0)));
      when(repo.saveAll(any())).thenAnswer(i -> {
        List<PairingCode> out = new ArrayList<>();
        for (PairingCode c : i.<Iterable<PairingCode>>getArgument(0)) {
          out.add(saveCode(c));
        }
        return out;
      });
      doAnswer(i -> codes.remove(i.<PairingCode>getArgument(0).getId()))
          .when(repo).delete(any(PairingCode.class));
      when(repo.findByCodeHash(anyString())).thenAnswer(i -> findCodes(
          c -> i.getArgument(0).equals(c.getCodeHash())).stream().findFirst());
      when(repo.findByClaimTokenHash(anyString())).thenAnswer(i -> findCodes(
          c -> i.getArgument(0).equals(c.getClaimTokenHash())).stream().findFirst());
      when(repo.findByShopIdAndEmployeeIdAndStatus(anyString(), anyString(), any()))
          .thenAnswer(i -> findCodes(c -> c.getShopId().equals(i.getArgument(0))
              && c.getEmployeeId().equals(i.getArgument(1)) && c.getStatus() == i.getArgument(2)));
      return repo;
    }

    // -- the MongoTemplate operations the service uses

    MongoTemplate mongoTemplate() {
      MongoTemplate template = mock(MongoTemplate.class);
      when(template.findAndModify(any(Query.class), any(Update.class),
          any(FindAndModifyOptions.class), any(Class.class))).thenAnswer(i -> {
            assertEquals(DevicePairing.class, i.getArgument(3));
            Document where = i.<Query>getArgument(0).getQueryObject();
            DevicePairing hit = pairings.values().stream().filter(p -> matches(p, where))
                .findFirst().orElse(null);
            if (hit == null) {
              return null;
            }
            DevicePairing before = copy(hit);
            applyUpdate(hit, i.<Update>getArgument(1).getUpdateObject());
            return i.<FindAndModifyOptions>getArgument(2).isReturnNew() ? copy(hit) : before;
          });
      when(template.updateFirst(any(Query.class), any(Update.class), any(Class.class)))
          .thenAnswer(i -> {
            assertEquals(DevicePairing.class, i.getArgument(2));
            Document where = i.<Query>getArgument(0).getQueryObject();
            DevicePairing hit = pairings.values().stream().filter(p -> matches(p, where))
                .findFirst().orElse(null);
            if (hit == null) {
              return UpdateResult.acknowledged(0, 0L, null);
            }
            applyUpdate(hit, i.<Update>getArgument(1).getUpdateObject());
            return UpdateResult.acknowledged(1, 1L, null);
          });
      return template;
    }

    /** Equality criteria only, which is all the service builds. */
    private static boolean matches(DevicePairing p, Document where) {
      for (Map.Entry<String, Object> e : where.entrySet()) {
        Object actual = switch (e.getKey()) {
          case "_id" -> p.getId();
          case "shopId" -> p.getShopId();
          case "employeeId" -> p.getEmployeeId();
          case "installationId" -> p.getInstallationId();
          case "status" -> p.getStatus();
          default -> throw new IllegalArgumentException("unexpected criterion " + e.getKey());
        };
        if (!Objects.equals(actual, e.getValue())) {
          return false;
        }
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    private static void applyUpdate(DevicePairing p, Document update) {
      BeanWrapperImpl bean = new BeanWrapperImpl(p);
      for (Map.Entry<String, Object> op : update.entrySet()) {
        Document fields = (Document) op.getValue();
        switch (op.getKey()) {
          case "$set" -> fields.forEach(bean::setPropertyValue);
          case "$unset" -> fields.keySet().forEach(k -> bean.setPropertyValue(k, null));
          case "$max" -> fields.forEach((k, v) -> {
            Comparable<Object> current = (Comparable<Object>) bean.getPropertyValue(k);
            if (current == null || current.compareTo(v) < 0) {
              bean.setPropertyValue(k, v);
            }
          });
          case "$push" -> fields.forEach((k, v) -> {
            List<Object> list = (List<Object>) bean.getPropertyValue(k);
            if (list == null) {
              list = new ArrayList<>();
              bean.setPropertyValue(k, list);
            }
            list.add(v);
          });
          default -> throw new IllegalArgumentException("unexpected operator " + op.getKey());
        }
      }
    }

    static DevicePairing copy(DevicePairing p) {
      DevicePairing c = new DevicePairing();
      BeanUtils.copyProperties(p, c);
      c.setConsents(p.getConsents() != null ? new ArrayList<>(p.getConsents()) : null);
      return c;
    }

    static PairingCode copy(PairingCode code) {
      PairingCode c = new PairingCode();
      BeanUtils.copyProperties(code, c);
      return c;
    }
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
