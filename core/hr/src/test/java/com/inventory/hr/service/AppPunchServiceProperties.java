// Feature: HR-module, Property 2: Any tampering is refused and nothing is stored
// Feature: HR-module, Property 3: Replaying an accepted punch is idempotent
// Feature: HR-module, Property 4: Refusals follow the My HR order and store nothing
// Feature: HR-module, Property 5: Punch time source and offline windows
// Feature: HR-module, Property 12: History shows the caller's own punches in the window, newest first
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppPunchResponse.Outcome;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.service.DevicePairingServiceProperties.MutableClock;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPunchResult;
import com.inventory.hr.validation.AppPunchValidator;
import com.inventory.metrics.MetricsWrapper;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Properties 2, 3, 4, 5 and 12 of the HR-module design (task 5.8), run against the real {@link
 * AppPunchService} with the real {@link SelfCheckInService} refusal checks and the real {@link
 * AppPunchValidator}, over an in-memory punch store. Requests are signed with real P-256 keys over
 * the canonical payload; the clock is a {@link MutableClock} pinned per try. Leave, holiday and
 * lock reads are answered from flags on the harness, so every combination of the My HR checks can
 * be reached.
 *
 * <p><b>Property 2 — Validates: Requirements 3.2, 5.7, 5.8, 5.11, 9.8.</b> One change to a validly
 * signed punch (any of the ten payload fields, a signature from another key, a pairing that is
 * not active, a pairing whose shop or employee differs) is refused {@code HR_APP_NOT_VERIFIED}
 * (or {@code HR_APP_NOT_PAIRED} for an inactive pairing), inserts no punch, writes no derived day
 * and increments exactly one result counter.
 *
 * <p><b>Property 3 — Validates: Requirements 5.10, 6.7.</b> Resending an accepted punch any number
 * of times leaves exactly one punch under that dedupe key, answers {@code REPLAYED} with the
 * stored direction, time and flags, and the derived day is the one written after the first send.
 *
 * <p><b>Property 4 — Validates: Requirements 1.4, 4.3, 6.10, 10.1, 10.2.</b> For any combination
 * of switch, pairing status, employment on the punch day, leave (with or without a week-off or
 * holiday), lock and location inputs, the answer is the first failing check in the order switch →
 * not paired → not employed → on leave → locked → location, with the My HR message; a refusal
 * stores nothing; exactly one result label is incremented per attempt.
 *
 * <p><b>Property 5 — Validates: Requirements 4.4, 6.8, 6.9, 6.11, 6.12.</b> An online punch is
 * stored at the server's whole second whatever the phone said; an offline punch is refused {@code
 * HR_APP_CLOCK_WRONG} exactly when more than 5 minutes ahead, {@code HR_APP_TOO_LATE} exactly when
 * more than 24 hours old, else stored at the phone's time with its Asia/Kolkata day and the {@code
 * RECORDED_OFFLINE} flag; values on a boundary are accepted.
 *
 * <p><b>Property 12 — Validates: Requirements 4.10, 8.9.</b> History returns exactly the caller's
 * punches whose day lies in the window, newest first, each marked counted by the day's 5-minute
 * rule.
 */
class AppPunchServiceProperties {

  private static final String SHOP_ID = "5f1a2b3c4d5e6f7a8b9c0d1e";
  private static final String EMP_ID = "6a1b2c3d4e5f6a7b8c9d0e1f";
  private static final String PAIRING_ID = "7b2c3d4e5f6a7b8c9d0e1f2a";
  private static final String OTHER_EMP = "9c9c9c9c9c9c9c9c9c9c9c9c";
  private static final String THIRD_EMP = "8d8d8d8d8d8d8d8d8d8d8d8d";
  /** 09:12:00 IST on Tuesday 8 April 2025. */
  private static final Instant T0 = Instant.parse("2025-04-08T03:42:00Z");
  /** A small pool of P-256 keys; key 0 is the pairing's punch key, the others are strangers. */
  private static final List<KeyPair> KEYS = keys(4);

  // ---- Property 2 ----------------------------------------------------------------------------

  /** The one change made to a validly signed request (or to the pairing it is judged against). */
  enum Tamper {
    PAIRING_ID, SHOP_ID, EMPLOYEE_ID, PUNCH_ID, PHONE_TIME, OFFLINE, LOCATION_CHECKED,
    DISTANCE, ACCURACY, MOCK, OTHER_KEY, PAIRING_UNPAIRED, PAIRING_OTHER_SHOP,
    PAIRING_OTHER_EMPLOYEE
  }

  @Property(tries = 150)
  void anyTamperingIsRefusedAndNothingIsStored(@ForAll("tampers") Tamper tamper,
      @ForAll("goodFields") PunchFields fields, @ForAll("hex24") String otherId,
      @ForAll("seconds") int shift, @ForAll("metres") int metres,
      @ForAll boolean locationCheckOn) throws Exception {
    Harness h = new Harness(T0);
    if (locationCheckOn) {
      h.locationCheck(OutsideAction.FLAG, 100);
    }
    AppPunchRequest r = h.signed(fields);
    ErrorCode expected = ErrorCode.HR_APP_NOT_VERIFIED;
    String expectedLabel = AppPunchResult.SIGNATURE_FAILED;
    switch (tamper) {
      case PAIRING_ID -> r.setPairingId(otherId);
      case SHOP_ID -> r.setShopId(otherId);
      case EMPLOYEE_ID -> r.setEmployeeId(otherId);
      case PUNCH_ID -> r.setPunchId(UUID.randomUUID().toString().toLowerCase());
      case PHONE_TIME -> r.setPhoneTime(phoneTime(fields.phoneTime().plusSeconds(shift)));
      case OFFLINE -> r.setOffline(!fields.offline());
      case LOCATION_CHECKED -> r.setLocationChecked(!fields.locationChecked());
      case DISTANCE -> r.setDistanceMetres(different(fields.distanceMetres(), metres));
      case ACCURACY -> r.setAccuracyMetres(different(fields.accuracyMetres(), metres));
      case MOCK -> r.setMock(!fields.mock());
      case OTHER_KEY -> r.setSignature(sign(KEYS.get(1 + Math.abs(shift) % 3),
          AppSignatures.punchPayload(fields)));
      case PAIRING_UNPAIRED -> {
        h.pairing.setStatus(PairingStatus.UNPAIRED);
        expected = ErrorCode.HR_APP_NOT_PAIRED;
        expectedLabel = AppPunchResult.NOT_PAIRED;
      }
      case PAIRING_OTHER_SHOP -> h.pairing.setShopId(otherId);
      case PAIRING_OTHER_EMPLOYEE -> h.pairing.setEmployeeId(otherId);
    }

    BaseException e = assertThrows(BaseException.class, () -> h.service.punch(h.pairing, r),
        tamper + " must be refused");
    assertEquals(expected, e.getErrorCode(), tamper + " error code");
    assertEquals(expected.getMessage(), e.getMessage(), tamper + " message");
    assertTrue(h.stored.isEmpty(), tamper + ": a refusal stores nothing");
    assertTrue(h.derivedWrites.isEmpty(), tamper + ": a refusal writes no derived day");
    assertTrue(h.lastPunchRecorded.isEmpty(), tamper + ": a refusal records no last punch");
    assertEquals(List.of(expectedLabel), h.results, tamper + ": exactly one result label");
  }

  @Provide
  Arbitrary<Tamper> tampers() {
    return Arbitraries.of(Tamper.class);
  }

  /** A non-zero shift in seconds, so a changed phone time really is different. */
  @Provide
  Arbitrary<Integer> seconds() {
    return Arbitraries.integers().between(-7200, 7200).filter(s -> s != 0);
  }

  @Provide
  Arbitrary<Integer> metres() {
    return Arbitraries.integers().between(0, 2000);
  }

  /** Another 24-character lowercase id, never one of the fixture ids. */
  @Provide
  Arbitrary<String> hex24() {
    return Arbitraries.strings().withChars("0123456789abcdef").ofLength(24)
        .filter(s -> !Set.of(SHOP_ID, EMP_ID, PAIRING_ID, OTHER_EMP, THIRD_EMP).contains(s));
  }

  /** Valid punch fields for the fixture pairing, inside the offline windows at {@link #T0}. */
  @Provide
  Arbitrary<PunchFields> goodFields() {
    Arbitrary<Instant> phoneTime = Arbitraries.longs().between(-20 * 3600, 4 * 60)
        .map(T0::plusSeconds);
    Arbitrary<Integer> distance = Arbitraries.integers().between(0, 1500).injectNull(0.2);
    Arbitrary<Integer> accuracy = Arbitraries.integers().between(0, 400).injectNull(0.2);
    return Combinators.combine(phoneTime, Arbitraries.of(true, false), Arbitraries.of(true, false),
        distance, accuracy, Arbitraries.of(true, false))
        .as((t, offline, checked, d, a, mock) -> new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID,
            newPunchId(), t, offline, checked, d, a, mock));
  }

  private static Integer different(Integer current, int candidate) {
    if (current == null) {
      return candidate;
    }
    return candidate == current ? null : candidate;
  }

  // ---- Property 3 ----------------------------------------------------------------------------

  @Property(tries = 100)
  void replayingAnAcceptedPunchIsIdempotent(@ForAll("goodFields") PunchFields fields,
      @ForAll("replays") List<Tuple.Tuple2<Integer, Boolean>> replays) throws Exception {
    Harness h = new Harness(T0);
    h.locationCheck(OutsideAction.FLAG, 100);
    AppPunchRequest r = h.signed(fields);

    AppPunchResponse first = h.service.punch(h.pairing, r);
    assertEquals(Outcome.STORED, first.getOutcome());
    assertEquals(1, h.stored.size());
    assertEquals(1, h.derivedWrites.size(), "the first send writes the derived day once");
    Punch punch = h.stored.get(0);
    String dedupeKey = AppPunchService.dedupeKey(EMP_ID, fields.punchId());
    assertEquals(dedupeKey, punch.getDedupeKey());
    PunchDayDeriver.Derived firstDerived = h.derivedWrites.get(0);
    String firstLabel = h.results.get(0);
    Snapshot before = Snapshot.of(punch);

    List<String> expectedLabels = new ArrayList<>(List.of(firstLabel));
    for (Tuple.Tuple2<Integer, Boolean> step : replays) {
      h.clock.advance(Duration.ofMinutes(step.get1()));
      if (step.get2()) {
        // the switch may even have been turned off meanwhile: the stored answer still stands
        h.settings.getAttendanceCapture().setBiometricApp(false);
      }
      AppPunchResponse again = h.service.punch(h.pairing, r);
      expectedLabels.add(AppPunchResult.REPLAYED);

      assertEquals(Outcome.REPLAYED, again.getOutcome());
      assertEquals(first.getDirection(), again.getDirection());
      assertEquals(first.getAt(), again.getAt());
      assertEquals(first.getAtLocal(), again.getAtLocal());
      assertEquals(first.getFlags(), again.getFlags());
      assertFalse(again.isDuplicate(), "the only punch of the day is counted");
      assertEquals(first.getTodayIn(), again.getTodayIn());
      assertEquals(first.getTodayOut(), again.getTodayOut());
      assertEquals(first.getNextDirection(), again.getNextDirection());
    }

    assertEquals(1, h.stored.stream().filter(p -> dedupeKey.equals(p.getDedupeKey())).count(),
        "exactly one punch under the dedupe key");
    assertEquals(1, h.stored.size());
    assertEquals(before, Snapshot.of(h.stored.get(0)), "the stored punch is untouched");
    assertEquals(1, h.derivedWrites.size(), "a replay writes no derived day");
    assertEquals(1, h.lastPunchRecorded.size(), "a replay records no last punch");
    // the derived day is still the one written after the first send
    PunchDayDeriver.Derived now = PunchDayService.derive(h.stored, h.employee,
        punch.getLocalDate(), h.settings);
    assertEquals(ids(firstDerived.counted()), ids(now.counted()));
    assertEquals(firstDerived.inTime(), now.inTime());
    assertEquals(firstDerived.outTime(), now.outTime());
    assertEquals(firstDerived.primarySource(), now.primarySource());
    assertEquals(expectedLabels, h.results, "one result label per attempt");
  }

  /** Between 1 and 6 resends, each after 0–180 minutes, some after the switch was turned off. */
  @Provide
  Arbitrary<List<Tuple.Tuple2<Integer, Boolean>>> replays() {
    return Combinators.combine(Arbitraries.integers().between(0, 180),
        Arbitraries.frequency(Tuple.of(4, false), Tuple.of(1, true))).as(Tuple::of)
        .list().ofMinSize(1).ofMaxSize(6);
  }

  // ---- Property 4 ----------------------------------------------------------------------------

  /**
   * One attempt's world. Day offsets are relative to the punch's day (the server's today for an
   * online punch, the phone's day offline): the employee joined {@code joinOffset} days before it
   * and, when {@code exitOffset} is set, left {@code exitOffset} days after it.
   */
  record World(boolean switchOn, boolean paired, int joinOffset, Integer exitOffset,
      LeaveKind leave, boolean weekOff, boolean holiday, boolean locked,
      boolean locationCheck, OutsideAction action, int radius, boolean offline,
      int phoneOffsetMinutes, Integer distance, Integer accuracy, boolean mock) {}

  enum LeaveKind { NONE, FULL_DAY, HALF_DAY }

  /** What the model expects of one attempt. */
  record Verdict(ErrorCode code, String message, String label, Set<PunchFlag> flags) {
    static Verdict refused(ErrorCode code, String message, String label) {
      return new Verdict(code, message, label, null);
    }

    static Verdict accepted(Set<PunchFlag> flags, String label) {
      return new Verdict(null, null, label, flags);
    }
  }

  @Property(tries = 200)
  void refusalsFollowTheMyHrOrderAndStoreNothing(@ForAll("worlds") World w) throws Exception {
    Harness h = new Harness(T0);
    Instant phoneTime = T0.plus(Duration.ofMinutes(w.phoneOffsetMinutes()));
    LocalDate day = (w.offline() ? phoneTime : T0).atZone(HrDateUtils.ZONE).toLocalDate();
    String d = HrDateUtils.format(day);

    h.settings.getAttendanceCapture().setBiometricApp(w.switchOn());
    h.pairing.setStatus(w.paired() ? PairingStatus.ACTIVE : PairingStatus.UNPAIRED);
    h.employee.setJoinDate(HrDateUtils.format(day.minusDays(w.joinOffset())));
    h.employee.setExitDate(w.exitOffset() == null ? null
        : HrDateUtils.format(day.plusDays(w.exitOffset())));
    h.leave = w.leave();
    h.weekOff(day, w.weekOff());
    h.holiday = w.holiday();
    h.locked = w.locked();
    if (w.locationCheck()) {
      h.locationCheck(w.action(), w.radius());
    }
    PunchFields fields = new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, newPunchId(), phoneTime,
        w.offline(), w.locationCheck(), w.distance(), w.accuracy(), w.mock());
    Verdict expected = predict(w, day);

    try {
      AppPunchResponse r = h.service.punch(h.pairing, h.signed(fields));
      assertNull(expected.code(), w + ": expected refusal " + expected.code()
          + " but the punch was accepted with " + r.getFlags());
      assertEquals(Outcome.STORED, r.getOutcome());
      assertEquals(expected.flags(), flagSet(r.getFlags()), w + " flags");
      assertEquals(1, h.stored.size());
      assertEquals(d, h.stored.get(0).getLocalDate(), w + " day");
      assertEquals(1, h.derivedWrites.size(), w + ": one derived write");
    } catch (BaseException e) {
      assertNotNull(expected.code(), w + ": expected acceptance but got " + e.getErrorCode()
          + " / " + e.getMessage());
      assertEquals(expected.code(), e.getErrorCode(), w + " error code");
      assertEquals(expected.message(), e.getMessage(), w + " message");
      assertTrue(h.stored.isEmpty(), w + ": a refusal stores nothing");
      assertTrue(h.derivedWrites.isEmpty(), w + ": a refusal writes no derived day");
      assertTrue(h.lastPunchRecorded.isEmpty(), w + ": a refusal records no last punch");
    }
    assertEquals(List.of(expected.label()), h.results, w + ": exactly one result label");
  }

  /** The My HR order (R4.3), then the location rule (R4.5, R4.6), then the accepted label. */
  static Verdict predict(World w, LocalDate day) {
    if (!w.switchOn()) {
      return Verdict.refused(ErrorCode.HR_APP_OFF, ErrorCode.HR_APP_OFF.getMessage(),
          AppPunchResult.OFF);
    }
    if (!w.paired()) {
      return Verdict.refused(ErrorCode.HR_APP_NOT_PAIRED,
          ErrorCode.HR_APP_NOT_PAIRED.getMessage(), AppPunchResult.NOT_PAIRED);
    }
    // joined joinOffset days before the day (inclusive), left exitOffset days after (inclusive)
    boolean employed = w.joinOffset() >= 0 && (w.exitOffset() == null || w.exitOffset() >= 0);
    if (!employed) {
      return Verdict.refused(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED,
          SelfCheckInService.REASON_NOT_EMPLOYED, AppPunchResult.NOT_EMPLOYED);
    }
    // leave covers working days only: on a week-off or holiday it does not apply (R6.10)
    if (w.leave() == LeaveKind.FULL_DAY && !w.weekOff() && !w.holiday()) {
      return Verdict.refused(ErrorCode.HR_DAY_HAS_LEAVE, SelfCheckInService.REASON_ON_LEAVE,
          AppPunchResult.ON_LEAVE);
    }
    if (w.locked()) {
      return Verdict.refused(ErrorCode.HR_PERIOD_LOCKED, SelfCheckInService.REASON_LOCKED,
          AppPunchResult.LOCKED);
    }
    Set<PunchFlag> flags = EnumSet.noneOf(PunchFlag.class);
    if (w.locationCheck()) {
      boolean flag = w.action() == OutsideAction.FLAG;
      if (w.mock()) {
        if (!flag) {
          return Verdict.refused(ErrorCode.HR_FAKE_LOCATION,
              ErrorCode.HR_FAKE_LOCATION.getMessage(), AppPunchResult.FAKE_LOCATION);
        }
        flags.add(PunchFlag.FAKE_LOCATION);
      } else if (w.distance() == null) {
        if (!flag) {
          return Verdict.refused(ErrorCode.HR_OUTSIDE_SHOP,
              ErrorCode.HR_OUTSIDE_SHOP.getMessage(), AppPunchResult.OUTSIDE_SHOP);
        }
        flags.add(PunchFlag.NO_LOCATION);
      } else {
        if (w.accuracy() == null || w.accuracy() > SelfCheckInService.MAX_ACCURACY_METRES) {
          if (!flag) {
            return Verdict.refused(ErrorCode.HR_LOCATION_UNCLEAR,
                ErrorCode.HR_LOCATION_UNCLEAR.getMessage(), AppPunchResult.LOCATION_UNCLEAR);
          }
          flags.add(PunchFlag.LOW_ACCURACY);
        }
        if (w.distance() > w.radius()) {
          if (!flag) {
            return Verdict.refused(ErrorCode.HR_OUTSIDE_SHOP,
                ErrorCode.HR_OUTSIDE_SHOP.getMessage(), AppPunchResult.OUTSIDE_SHOP);
          }
          flags.add(PunchFlag.OUTSIDE_SHOP);
        }
      }
    }
    if (w.offline()) {
      flags.add(PunchFlag.RECORDED_OFFLINE);
      return Verdict.accepted(flags, AppPunchResult.OFFLINE_ACCEPTED);
    }
    return Verdict.accepted(flags,
        flags.isEmpty() ? AppPunchResult.ACCEPTED : AppPunchResult.FLAGGED);
  }

  /** The My HR checks of a {@link World}. */
  record Checks(boolean switchOn, boolean paired, int joinOffset, Integer exitOffset,
      LeaveKind leave, boolean weekOff, boolean holiday, boolean locked) {}

  /** The punch and location inputs of a {@link World}. */
  record Inputs(boolean locationCheck, OutsideAction action, int radius, boolean offline,
      int phoneOffsetMinutes, Integer distance, Integer accuracy, boolean mock) {}

  @Provide
  Arbitrary<World> worlds() {
    Arbitrary<Boolean> bool = Arbitraries.of(true, false);
    Arbitrary<Boolean> mostlyTrue = Arbitraries.frequency(Tuple.of(3, true), Tuple.of(1, false));
    Arbitrary<Boolean> rare = Arbitraries.frequency(Tuple.of(3, false), Tuple.of(1, true));
    Arbitrary<Integer> joinOffset = Arbitraries.frequency(
        Tuple.of(5, 400), Tuple.of(2, 0), Tuple.of(1, 1), Tuple.of(2, -1), Tuple.of(1, -30));
    Arbitrary<Integer> exitOffset = Arbitraries.frequency(
        Tuple.of(2, 0), Tuple.of(2, 1), Tuple.of(2, -1), Tuple.of(1, -3)).injectNull(0.5);
    Arbitrary<Checks> checks = Combinators.combine(mostlyTrue, mostlyTrue, joinOffset, exitOffset,
        Arbitraries.of(LeaveKind.class), rare, rare, rare).as(Checks::new);

    Arbitrary<Integer> radius = Arbitraries.integers().between(30, 1000);
    // the phone's time stays inside the offline windows; it may fall on yesterday
    Arbitrary<Integer> phoneOffset = Arbitraries.integers().between(-23 * 60, 5);
    Arbitrary<Integer> distance = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 1500)),
        Tuple.of(1, Arbitraries.integers().between(0, 60)),
        Tuple.of(1, Arbitraries.integers().between(950, 1050))).injectNull(0.15);
    Arbitrary<Integer> accuracy = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 400)),
        Tuple.of(1, Arbitraries.of(199, 200, 201))).injectNull(0.15);
    Arbitrary<Inputs> inputs = Combinators.combine(bool, Arbitraries.of(OutsideAction.class),
        radius, bool, phoneOffset, distance, accuracy, rare).as(Inputs::new);

    return Combinators.combine(checks, inputs).as((c, i) -> new World(c.switchOn(), c.paired(),
        c.joinOffset(), c.exitOffset(), c.leave(), c.weekOff(), c.holiday(), c.locked(),
        i.locationCheck(), i.action(), i.radius(), i.offline(), i.phoneOffsetMinutes(),
        i.distance(), i.accuracy(), i.mock()));
  }

  // ---- Property 5 ----------------------------------------------------------------------------

  @Property(tries = 200)
  void punchTimeComesFromTheRightSourceAndOfflineWindowsHold(@ForAll("nows") Instant now,
      @ForAll("phoneOffsets") long offsetSeconds, @ForAll boolean offline) throws Exception {
    Harness h = new Harness(now);
    Instant nowSeconds = now.truncatedTo(ChronoUnit.SECONDS);
    Instant phoneTime = nowSeconds.plusSeconds(offsetSeconds);
    PunchFields fields = new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, newPunchId(), phoneTime,
        offline, false, null, null, false);

    ErrorCode expectedRefusal = null;
    if (offline && phoneTime.isAfter(nowSeconds.plus(AppPunchService.CLOCK_AHEAD_MAX))) {
      expectedRefusal = ErrorCode.HR_APP_CLOCK_WRONG;
    } else if (offline && phoneTime.isBefore(nowSeconds.minus(AppPunchService.OFFLINE_MAX_AGE))) {
      expectedRefusal = ErrorCode.HR_APP_TOO_LATE;
    }

    if (expectedRefusal != null) {
      BaseException e = assertThrows(BaseException.class,
          () -> h.service.punch(h.pairing, h.signed(fields)));
      assertEquals(expectedRefusal, e.getErrorCode(), "offset " + offsetSeconds + " s");
      assertTrue(h.stored.isEmpty());
      assertTrue(h.derivedWrites.isEmpty());
      assertEquals(List.of(expectedRefusal == ErrorCode.HR_APP_CLOCK_WRONG
          ? AppPunchResult.OFFLINE_CLOCK_WRONG : AppPunchResult.OFFLINE_TOO_LATE), h.results);
      return;
    }

    AppPunchResponse r = h.service.punch(h.pairing, h.signed(fields));

    assertEquals(Outcome.STORED, r.getOutcome(), "offset " + offsetSeconds + " s");
    Instant expectedAt = offline ? phoneTime : nowSeconds;
    Punch p = h.stored.get(0);
    assertEquals(expectedAt, r.getAt());
    assertEquals(expectedAt, p.getAt());
    assertEquals(0, p.getAt().getNano(), "whole seconds");
    assertEquals(HrDateUtils.format(expectedAt.atZone(HrDateUtils.ZONE).toLocalDate()),
        p.getLocalDate(), "the day is the punch time's day in Asia/Kolkata");
    assertEquals(phoneTime, p.getPhoneTime());
    assertEquals(offline, p.getFlags().contains(PunchFlag.RECORDED_OFFLINE));
    assertEquals(offline, r.getFlags().contains(PunchFlag.RECORDED_OFFLINE));
    assertEquals(List.of(expectedAt), h.lastPunchRecorded);
    assertEquals(1, h.derivedWrites.size());
    assertEquals(List.of(offline ? AppPunchResult.OFFLINE_ACCEPTED : AppPunchResult.ACCEPTED),
        h.results);
  }

  /** Any server time over three years, with sub-second parts the service must truncate. */
  @Provide
  Arbitrary<Instant> nows() {
    long from = Instant.parse("2024-01-01T00:00:00Z").getEpochSecond();
    long to = Instant.parse("2026-12-31T23:59:59Z").getEpochSecond();
    return Combinators.combine(Arbitraries.longs().between(from, to),
        Arbitraries.integers().between(0, 999_999_999))
        .as((s, n) -> Instant.ofEpochSecond(s, n));
  }

  /** Phone time minus server time, in seconds; the two boundaries and their neighbours are hot. */
  @Provide
  Arbitrary<Long> phoneOffsets() {
    long ahead = AppPunchService.CLOCK_AHEAD_MAX.toSeconds();
    long age = AppPunchService.OFFLINE_MAX_AGE.toSeconds();
    return Arbitraries.frequencyOf(
        Tuple.of(6, Arbitraries.longs().between(-age - 7200, ahead + 7200)),
        Tuple.of(1, Arbitraries.of(ahead, ahead + 1, ahead - 1)),
        Tuple.of(1, Arbitraries.of(-age, -age - 1, -age + 1)),
        Tuple.of(1, Arbitraries.longs().between(-30L * 86400, 30L * 86400)));
  }

  // ---- Property 12 ---------------------------------------------------------------------------

  /** One stored punch: which employee, how many days before today, and when that day. */
  record Stored(int employee, int daysAgo, int secondOfDay, PunchSource source,
      boolean offlineFlag) {}

  @Property(tries = 100)
  void historyShowsTheCallersOwnPunchesInTheWindowNewestFirst(
      @ForAll("storedPunches") List<Stored> specs, @ForAll("ranges") Tuple.Tuple2<Integer, Integer> range) {
    Harness h = new Harness(T0);
    LocalDate today = T0.atZone(HrDateUtils.ZONE).toLocalDate();
    List<String> employees = List.of(EMP_ID, OTHER_EMP, THIRD_EMP);
    int i = 0;
    for (Stored s : specs) {
      LocalDate day = today.minusDays(s.daysAgo());
      Punch p = new Punch();
      p.setId(String.format("p%03d", i++));
      p.setShopId(SHOP_ID);
      p.setEmployeeId(employees.get(s.employee()));
      p.setAt(day.atStartOfDay(HrDateUtils.ZONE).toInstant().plusSeconds(s.secondOfDay()));
      p.setLocalDate(HrDateUtils.format(day));
      p.setSource(s.source());
      p.setDirection(PunchDirection.IN);
      p.setFlags(new ArrayList<>(s.offlineFlag() ? List.of(PunchFlag.RECORDED_OFFLINE)
          : List.of()));
      h.stored.add(p);
    }

    LocalDate from;
    LocalDate to;
    List<PunchResponse> rows;
    if (range == null) {
      to = today;
      from = today.minusDays(AppPunchService.HISTORY_MAX_DAYS - 1);
      rows = h.service.myPunches(h.pairing, null, null);
    } else {
      to = today.minusDays(range.get2());
      from = to.minusDays(range.get1());
      rows = h.service.myPunches(h.pairing, HrDateUtils.format(from), HrDateUtils.format(to));
    }

    // exactly the caller's punches of the window, newest first (ties: as the store orders them)
    List<Punch> expected = h.stored.stream()
        .filter(p -> EMP_ID.equals(p.getEmployeeId()))
        .filter(p -> !HrDateUtils.parseDate(p.getLocalDate()).isBefore(from)
            && !HrDateUtils.parseDate(p.getLocalDate()).isAfter(to))
        .sorted(Harness.NEWEST_FIRST)
        .toList();
    assertEquals(expected.stream().map(Punch::getId).toList(),
        rows.stream().map(PunchResponse::getId).toList());
    for (int k = 1; k < rows.size(); k++) {
      assertFalse(rows.get(k).getAt().isAfter(rows.get(k - 1).getAt()), "newest first");
    }
    for (PunchResponse row : rows) {
      assertEquals(EMP_ID, row.getEmployeeId(), "never another employee's punch");
      assertFalse(HrDateUtils.parseDate(row.getLocalDate()).isBefore(from), "inside the window");
      assertFalse(HrDateUtils.parseDate(row.getLocalDate()).isAfter(to), "inside the window");
    }

    // counted: the day's 5-minute rule over the caller's punches of that day (oldest first,
    // id breaks ties), worked out independently of the deriver
    Set<String> counted = countedByModel(expected);
    for (PunchResponse row : rows) {
      assertEquals(counted.contains(row.getId()), row.isCounted(), row.getId() + " counted");
    }
  }

  static Set<String> countedByModel(List<Punch> punches) {
    Map<String, List<Punch>> byDay = new TreeMap<>();
    for (Punch p : punches) {
      byDay.computeIfAbsent(p.getLocalDate(), x -> new ArrayList<>()).add(p);
    }
    Set<String> counted = new HashSet<>();
    for (List<Punch> day : byDay.values()) {
      day.sort(Comparator.comparing(Punch::getAt).thenComparing(Punch::getId));
      Instant last = null;
      for (Punch p : day) {
        if (last == null || Duration.between(last, p.getAt())
            .compareTo(PunchDayDeriver.DUPLICATE_WINDOW) >= 0) {
          counted.add(p.getId());
          last = p.getAt();
        }
      }
    }
    return counted;
  }

  @Provide
  Arbitrary<List<Stored>> storedPunches() {
    Arbitrary<Integer> employee = Arbitraries.frequency(Tuple.of(3, 0), Tuple.of(1, 1),
        Tuple.of(1, 2));
    // inside and outside the 31-day window, including its two edges and tomorrow
    Arbitrary<Integer> daysAgo = Arbitraries.frequencyOf(
        Tuple.of(4, Arbitraries.integers().between(0, 40)),
        Tuple.of(1, Arbitraries.of(0, 30, 31, -1)));
    // times cluster so the 5-minute rule has duplicates to find
    Arbitrary<Integer> second = Arbitraries.frequencyOf(
        Tuple.of(2, Arbitraries.integers().between(0, 86399)),
        Tuple.of(3, Arbitraries.integers().between(9 * 3600, 9 * 3600 + 900)));
    Arbitrary<PunchSource> source = Arbitraries.of(PunchSource.SELF, PunchSource.BIOMETRIC_APP);
    return Combinators.combine(employee, daysAgo, second, source, Arbitraries.of(true, false))
        .as(Stored::new).list().ofMaxSize(40);
  }

  /** null = the default window; else (span − 1, days before today the range ends), span ≤ 31. */
  @Provide
  Arbitrary<Tuple.Tuple2<Integer, Integer>> ranges() {
    return Combinators.combine(Arbitraries.integers().between(0, AppPunchService.HISTORY_MAX_DAYS - 1),
        Arbitraries.integers().between(0, 10)).as(Tuple::of).injectNull(0.4);
  }

  // ---- harness -------------------------------------------------------------------------------

  /** The fields of a stored punch that a replay must not touch. */
  record Snapshot(String id, Instant at, String localDate, PunchDirection direction,
      List<PunchFlag> flags, Integer distance, Integer accuracy, String dedupeKey,
      String payloadSha256, Instant phoneTime, Instant createdAt) {
    static Snapshot of(Punch p) {
      return new Snapshot(p.getId(), p.getAt(), p.getLocalDate(), p.getDirection(),
          List.copyOf(p.getFlags()), p.getDistanceMetres(), p.getAccuracyMetres(),
          p.getDedupeKey(), p.getPayloadSha256(), p.getPhoneTime(), p.getCreatedAt());
    }
  }

  /**
   * The real {@link AppPunchService}, {@link SelfCheckInService} and {@link AppPunchValidator}
   * over an in-memory punch list; leave, holiday and lock reads answer from the flags below.
   */
  static final class Harness {
    /** How the Mongo stand-in orders a history read: {@code at} descending, id breaks ties. */
    static final Comparator<Punch> NEWEST_FIRST = Comparator.comparing(Punch::getAt)
        .thenComparing(Punch::getId).reversed();

    final MutableClock clock;
    final AppPunchService service;
    final HrSettings settings;
    final Employee employee;
    final DevicePairing pairing;
    final List<Punch> stored = new ArrayList<>();
    final List<PunchDayDeriver.Derived> derivedWrites = new ArrayList<>();
    final List<Instant> lastPunchRecorded = new ArrayList<>();
    /** Every {@code inventory_hr_app_punches_total} result label, in order. */
    final List<String> results = new ArrayList<>();
    LeaveKind leave = LeaveKind.NONE;
    boolean holiday;
    boolean locked;

    Harness(Instant now) {
      clock = new MutableClock(now);

      settings = HrSettingsService.withDefaults(null, SHOP_ID);
      settings.getAttendanceCapture().setBiometricApp(true);
      // no weekly off unless a property asks for one
      settings.setWeeklyOffSchedule(new ArrayList<>(List.of(
          new HrSettings.WeeklyOffEntry(HrSettingsService.SCHEDULE_START, List.of()))));
      HrSettingsService settingsService = mock(HrSettingsService.class);
      when(settingsService.effectiveSettings(anyString())).thenReturn(settings);

      employee = new Employee();
      employee.setId(EMP_ID);
      employee.setShopId(SHOP_ID);
      employee.setName("Ravi Kumar");
      employee.setJoinDate("2020-01-01");
      employee.setStatus(EmployeeStatus.ACTIVE);
      EmployeeRepository employees = mock(EmployeeRepository.class);
      when(employees.findByShopIdAndId(anyString(), anyString()))
          .thenReturn(Optional.of(employee));

      pairing = new DevicePairing();
      pairing.setId(PAIRING_ID);
      pairing.setShopId(SHOP_ID);
      pairing.setEmployeeId(EMP_ID);
      pairing.setStatus(PairingStatus.ACTIVE);
      pairing.setPunchPublicKey(spki(KEYS.get(0)));
      pairing.setPhoneModel("Redmi Note 11");
      pairing.setConsentVersion(1);

      PunchDayService punchDays = mock(PunchDayService.class);
      when(punchDays.punchesOf(anyString(), anyString(), anyString())).thenAnswer(i -> {
        String employeeId = i.getArgument(1);
        String date = i.getArgument(2);
        return stored.stream().filter(p -> employeeId.equals(p.getEmployeeId())
            && date.equals(p.getLocalDate())).sorted(Comparator.comparing(Punch::getAt)).toList();
      });
      doAnswer(i -> {
        derivedWrites.add(i.getArgument(4));
        return null;
      }).when(punchDays).writeDerived(any(), any(), any(), any(), any());

      PunchRepository punchRepository = mock(PunchRepository.class);
      when(punchRepository.findByShopIdAndDedupeKey(anyString(), anyString())).thenAnswer(i -> {
        String shopId = i.getArgument(0);
        String key = i.getArgument(1);
        return stored.stream().filter(p -> shopId.equals(p.getShopId())
            && key.equals(p.getDedupeKey())).findFirst();
      });
      when(punchRepository.insert(any(Punch.class))).thenAnswer(i -> {
        Punch p = i.getArgument(0);
        p.setId(String.format("p%03d", stored.size() + 1));
        stored.add(p);
        return p;
      });
      when(punchRepository.findRangeNewestFirst(anyString(), anyString(), anyString(),
          anyString())).thenAnswer(i -> {
            String shopId = i.getArgument(0);
            String employeeId = i.getArgument(1);
            String from = i.getArgument(2);
            String to = i.getArgument(3);
            return stored.stream().filter(p -> shopId.equals(p.getShopId())
                && employeeId.equals(p.getEmployeeId())
                && p.getLocalDate().compareTo(from) >= 0 && p.getLocalDate().compareTo(to) <= 0)
                .sorted(NEWEST_FIRST).toList();
          });

      DevicePairingService pairingService = mock(DevicePairingService.class);
      when(pairingService.shopName(anyString())).thenReturn("Kumar Stores");
      doAnswer(i -> {
        lastPunchRecorded.add(i.getArgument(1));
        return null;
      }).when(pairingService).recordLastPunch(anyString(), any(Instant.class));

      AppRateLimiter rateLimiter = mock(AppRateLimiter.class);
      when(rateLimiter.tryAcquirePunch(anyString())).thenReturn(true);

      ConsentVersions consentVersions = mock(ConsentVersions.class);
      when(consentVersions.latest()).thenReturn(new ConsentVersions.ConsentVersion(1,
          ConsentVersions.Kind.RECONSENT_REQUIRED, List.of(), List.of(), List.of()));

      MetricsWrapper metrics = mock(MetricsWrapper.class);
      doAnswer(i -> {
        if (HrMetricsConstants.APP_PUNCHES_TOTAL.equals(i.getArgument(0))) {
          // Mockito hands varargs over flattened: the tags start at index 2
          Object[] args = i.getArguments();
          List<Object> tags = Arrays.asList(args).subList(2, args.length);
          assertEquals(6, tags.size(), "module, shop and result tags");
          assertEquals(HrMetricsConstants.TAG_SHOP, tags.get(2));
          assertEquals(pairing.getShopId(), tags.get(3), "counted for the pairing's shop");
          assertEquals(HrMetricsConstants.TAG_RESULT, tags.get(4));
          results.add((String) tags.get(5));
        }
        return null;
      }).when(metrics).increment(anyString(), anyDouble(), any(String[].class));

      // the real refusal checks over leave / holiday / lock reads answered from the flags
      AttendanceService attendanceService = mock(AttendanceService.class);
      when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
          anyLong())).thenAnswer(i -> {
            if (leave == LeaveKind.NONE) {
              return List.of();
            }
            LeaveRequest l = new LeaveRequest();
            l.setId("leave-1");
            l.setEmployeeId(EMP_ID);
            l.setFromDate(i.getArgument(2));
            l.setToDate(i.getArgument(3));
            l.setHalfDay(leave == LeaveKind.HALF_DAY);
            return List.of(l);
          });
      when(attendanceService.holidayDates(anyString(), anyString(), anyString(), anyLong()))
          .thenAnswer(i -> holiday ? Set.of((String) i.getArgument(1)) : Set.of());
      PeriodLockService periodLockService = mock(PeriodLockService.class);
      when(periodLockService.lockedPeriods(anyString(), anyCollection())).thenAnswer(
          i -> locked ? new HashSet<>(i.<Collection<String>>getArgument(1)) : Set.of());

      SelfCheckInService selfCheckIn = new SelfCheckInService();
      ReflectionTestUtils.setField(selfCheckIn, "settingsService", settingsService);
      ReflectionTestUtils.setField(selfCheckIn, "selfServiceResolver",
          mock(SelfServiceResolver.class));
      ReflectionTestUtils.setField(selfCheckIn, "hrAccessService", mock(HrAccessService.class));
      ReflectionTestUtils.setField(selfCheckIn, "attendanceService", attendanceService);
      ReflectionTestUtils.setField(selfCheckIn, "periodLockService", periodLockService);
      ReflectionTestUtils.setField(selfCheckIn, "punchDays", punchDays);
      ReflectionTestUtils.setField(selfCheckIn, "punchRepository", punchRepository);
      ReflectionTestUtils.setField(selfCheckIn, "metrics", metrics);

      AppPunchHealthService health = mock(AppPunchHealthService.class);
      doAnswer(i -> null).when(health).record(anyString(), anyBoolean());

      service = new AppPunchService();
      ReflectionTestUtils.setField(service, "settingsService", settingsService);
      ReflectionTestUtils.setField(service, "employeeRepository", employees);
      ReflectionTestUtils.setField(service, "selfCheckIn", selfCheckIn);
      ReflectionTestUtils.setField(service, "punchDays", punchDays);
      ReflectionTestUtils.setField(service, "punchRepository", punchRepository);
      ReflectionTestUtils.setField(service, "pairingService", pairingService);
      ReflectionTestUtils.setField(service, "rateLimiter", rateLimiter);
      ReflectionTestUtils.setField(service, "validator", new AppPunchValidator());
      ReflectionTestUtils.setField(service, "consentVersions", consentVersions);
      ReflectionTestUtils.setField(service, "metrics", metrics);
      ReflectionTestUtils.setField(service, "health", health);
      service.setClock(clock);
    }

    void locationCheck(OutsideAction action, int radius) {
      HrSettings.AttendanceCapture c = settings.getAttendanceCapture();
      c.setLocationCheck(true);
      c.setShopLat(12.9716);
      c.setShopLng(77.5946);
      c.setRadiusMetres(radius);
      c.setOutsideAction(action);
    }

    /** Makes {@code day}'s weekday a weekly off (or none at all). */
    void weekOff(LocalDate day, boolean on) {
      List<DayOfWeek> days = on ? List.of(day.getDayOfWeek()) : List.of();
      settings.setWeeklyOffSchedule(new ArrayList<>(List.of(
          new HrSettings.WeeklyOffEntry(HrSettingsService.SCHEDULE_START, days))));
    }

    /** A request signed by the pairing's punch key over exactly these fields. */
    AppPunchRequest signed(PunchFields f) throws GeneralSecurityException {
      AppPunchRequest r = new AppPunchRequest();
      r.setPairingId(f.pairingId());
      r.setShopId(f.shopId());
      r.setEmployeeId(f.employeeId());
      r.setPunchId(f.punchId());
      r.setPhoneTime(phoneTime(f.phoneTime()));
      r.setOffline(f.offline());
      r.setLocationChecked(f.locationChecked());
      r.setDistanceMetres(f.distanceMetres());
      r.setAccuracyMetres(f.accuracyMetres());
      r.setMock(f.mock());
      r.setSignature(sign(KEYS.get(0), AppSignatures.punchPayload(f)));
      return r;
    }
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static List<KeyPair> keys(int n) {
    try {
      KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
      g.initialize(new ECGenParameterSpec("secp256r1"));
      List<KeyPair> keys = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        keys.add(g.generateKeyPair());
      }
      return List.copyOf(keys);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String spki(KeyPair kp) {
    return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
  }

  private static String sign(KeyPair kp, byte[] payload) throws GeneralSecurityException {
    Signature s = Signature.getInstance("SHA256withECDSA");
    s.initSign(kp.getPrivate());
    s.update(payload);
    return Base64.getEncoder().encodeToString(s.sign());
  }

  private static String newPunchId() {
    return UUID.randomUUID().toString().toLowerCase();
  }

  /** The wire form of a phone time: UTC, whole seconds, {@code Z}. */
  private static String phoneTime(Instant t) {
    return t.truncatedTo(ChronoUnit.SECONDS).toString();
  }

  private static Set<PunchFlag> flagSet(List<PunchFlag> flags) {
    Set<PunchFlag> set = EnumSet.noneOf(PunchFlag.class);
    if (flags != null) {
      set.addAll(flags);
    }
    return set;
  }

  private static List<String> ids(List<Punch> punches) {
    return punches.stream().map(p -> Objects.requireNonNullElse(p.getId(), p.getDedupeKey()))
        .toList();
  }


}
