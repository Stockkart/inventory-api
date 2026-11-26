// Feature: worker-app, Property 26: A punch never needs an account
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.service.AppPunchServiceProperties.Harness;
import com.inventory.hr.service.AppPunchServiceProperties.World;
import com.inventory.hr.utils.AppSignatures.PunchFields;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.AppPunchValidator;
import com.inventory.user.api.WorkerAccounts;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Worker-app design Property 26 (R9.10): a punch never needs an account. The result of an app punch
 * is the HR-module result for the same punch fields, whatever the state of the login side. Built on the {@link
 * AppPunchServiceProperties} fixtures: the same {@link Harness} (real {@link AppPunchService},
 * {@link SelfCheckInService} and {@link AppPunchValidator} over an in-memory punch store, real
 * P-256 signatures, pinned clock) and the same {@link World} generator and {@link
 * AppPunchServiceProperties#predict oracle}, so every refusal and acceptance of the HR-module
 * design is covered, not only the happy path.
 *
 * <p>The property runs each generated punch twice: once in the <b>reference</b> world of HR-module
 * (the pairing's employee record carries no login, so nothing on the login side can matter) and
 * once in a <b>variant</b> world that differs only on the login side — the employee is linked to a
 * WORKER account whose session is live, expired, revoked or logged out, that meanwhile joined
 * another shop or was deleted, or to a staff account (R5.6). The two runs must agree on everything
 * the design names: the outcome or refusal code and text, the flags, the direction and times, the
 * stored punch and the derived day, and the one result label counted. The variant also carries a
 * fake account port that records every call; the punch path makes none (R9.9: the account and the
 * pairing's employee are never compared on a punch).
 *
 * <p>Two pins make the "never consulted" claim structural, not only observed: no class on the
 * punch path ({@link AppPunchService}, {@link SelfCheckInService}, {@link PunchDayService}, {@link
 * PunchDayDeriver}, {@link AppPunchValidator}) declares a field of a user-module type, so no account
 * can reach a punch decision; and the fake {@link WorkerAccounts} cannot be wired into any of them.
 *
 * <p><b>Validates: Requirements 7.6, 9.10</b>
 */
class AppPunchServiceAccountIndependenceProperties {

  /** 09:12:00 IST on Tuesday 8 April 2025, the fixture clock of {@link AppPunchServiceProperties}. */
  private static final Instant T0 = Instant.parse("2025-04-08T03:42:00Z");
  private static final String SHOP_ID = "5f1a2b3c4d5e6f7a8b9c0d1e";
  private static final String EMP_ID = "6a1b2c3d4e5f6a7b8c9d0e1f";
  private static final String PAIRING_ID = "7b2c3d4e5f6a7b8c9d0e1f2a";
  private static final String OTHER_SHOP = "4e4e4e4e4e4e4e4e4e4e4e4e";

  /** Classes a punch goes through, from the controller's call to the stored row. */
  private static final List<Class<?>> PUNCH_PATH = List.of(AppPunchService.class,
      SelfCheckInService.class, PunchDayService.class, PunchDayDeriver.class,
      AppPunchValidator.class);

  /**
   * The state of the login side the variant world is in (R9.10: live, expired or logged-out session,
   * whatever the role). {@code userId} is what the employee record carries, the one thing on the
   * punch path's side of the fence; everything else about the account — its sessions included —
   * lives in the user module and is reached only through {@link WorkerAccounts}.
   */
  enum AccountState {
    /** The reference world: the employee record carries no login. */
    NO_ACCOUNT(null),
    /** Linked to a WORKER account with a live session in this shop. */
    WORKER_SIGNED_IN("acct-worker"),
    /** Linked to a WORKER account whose session token has expired (R9.3, R9.4). */
    WORKER_SESSION_EXPIRED("acct-worker"),
    /** Linked to a WORKER account whose session token was revoked server-side (R9.3). */
    WORKER_SESSION_REVOKED("acct-worker"),
    /** Linked to a WORKER account whose sessions were all ended by Log out (R9.5, R9.6). */
    WORKER_LOGGED_OUT("acct-worker"),
    /** Linked to a WORKER account that meanwhile joined another shop (R5.1 edge). */
    WORKER_IN_OTHER_SHOP("acct-worker"),
    /** Linked to an account that no longer exists. */
    ACCOUNT_DELETED("acct-gone"),
    /** Linked to a staff account (OWNER / MANAGER / CASHIER) with its own membership. */
    STAFF("acct-staff");

    final String userId;

    AccountState(String userId) {
      this.userId = userId;
    }

    /** What the fake account port would answer, were anybody to ask. */
    Optional<WorkerAccounts.AccountView> view() {
      return switch (this) {
        case NO_ACCOUNT, ACCOUNT_DELETED -> Optional.empty();
        case WORKER_SIGNED_IN, WORKER_SESSION_EXPIRED, WORKER_SESSION_REVOKED, WORKER_LOGGED_OUT ->
            Optional.of(new WorkerAccounts.AccountView(
            userId, "Ravi Kumar", "ravi@example.com", "9876543210", "WORKER", SHOP_ID, true));
        case WORKER_IN_OTHER_SHOP -> Optional.of(new WorkerAccounts.AccountView(
            userId, "Ravi Kumar", "ravi@example.com", "9876543210", "WORKER", OTHER_SHOP, true));
        case STAFF -> Optional.of(new WorkerAccounts.AccountView(
            userId, "Ravi Kumar", "ravi@example.com", "9876543210", "MANAGER", SHOP_ID, false));
      };
    }
  }

  // ---- the structural pins ---------------------------------------------------------------------

  @Example
  void nothingOnThePunchPathCanReachAnAccount() {
    List<String> offenders = new ArrayList<>();
    for (Class<?> c : PUNCH_PATH) {
      for (Field f : c.getDeclaredFields()) {
        if (Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        String type = f.getType().getName();
        if (type.startsWith("com.inventory.user.")
            || WorkerAccounts.class.isAssignableFrom(f.getType())) {
          offenders.add(c.getSimpleName() + "." + f.getName() + " : " + type);
        }
      }
    }
    assertTrue(offenders.isEmpty(), "the punch path must not hold an account dependency "
        + "(R9.9, R9.10): " + offenders);
  }

  // ---- the behavioural property ----------------------------------------------------------------

  @Property(tries = 200)
  void aPunchAnswersTheSameWhateverTheAccountSideLooksLike(@ForAll("worlds") World w,
      @ForAll AccountState state) throws Exception {
    // the same punch fields for both runs: one punch id, one phone time, one signature
    Instant phoneTime = T0.plus(Duration.ofMinutes(w.phoneOffsetMinutes()));
    PunchFields fields = new PunchFields(PAIRING_ID, SHOP_ID, EMP_ID, newPunchId(), phoneTime,
        w.offline(), w.locationCheck(), w.distance(), w.accuracy(), w.mock());

    Harness reference = world(w, phoneTime);
    Harness variant = world(w, phoneTime);
    CountingAccounts accounts = new CountingAccounts(state);

    // the login side of the variant: the employee record is the only place the punch path
    // could see a link; the account itself is behind the port
    variant.employee.setUserId(state.userId);
    // the fake port is offered to every class on the path; none has a place for it
    for (Object bean : List.of(variant.service,
        ReflectionTestUtils.getField(variant.service, "selfCheckIn"))) {
      assertEquals(0, wire(bean, accounts), "no field takes the account port");
    }

    Run expected = run(reference, fields);
    Run actual = run(variant, fields);

    assertEquals(expected, actual, "state " + state + ", world " + w);
    assertEquals(0, accounts.calls.get(), "the account layer was consulted for a punch");
    // and the oracle of HR-module Property 4 still holds for the variant, so the agreement is
    // not two identical mistakes
    LocalDate day = (w.offline() ? phoneTime : T0).atZone(HrDateUtils.ZONE).toLocalDate();
    AppPunchServiceProperties.Verdict verdict = AppPunchServiceProperties.predict(w, day);
    if (verdict.code() == null) {
      assertNull(actual.refusalCode(), "the oracle expects acceptance");
      assertNotNull(actual.flags());
      Set<PunchFlag> flags = EnumSet.noneOf(PunchFlag.class);
      flags.addAll(actual.flags());
      assertEquals(verdict.flags(), flags, "the oracle's flags");
    } else {
      assertEquals(verdict.code(), actual.refusalCode(), "the oracle's refusal");
      assertEquals(verdict.message(), actual.refusalMessage());
    }
    assertEquals(List.of(verdict.label()), actual.results(), "exactly one result label");
  }

  /** The generator of HR-module Property 4: every combination of the My HR checks and inputs. */
  @Provide
  Arbitrary<World> worlds() {
    return new AppPunchServiceProperties().worlds();
  }

  // ---- fixture -----------------------------------------------------------------------------------

  /** A harness in the world {@code w}, exactly as HR-module Property 4 sets it up. */
  private static Harness world(World w, Instant phoneTime) {
    Harness h = new Harness(T0);
    LocalDate day = (w.offline() ? phoneTime : T0).atZone(HrDateUtils.ZONE).toLocalDate();
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
    return h;
  }

  /** Everything the design names as "the result" of a punch, comparable across two runs. */
  record Run(ErrorCode refusalCode, String refusalMessage, AppPunchResponse.Outcome outcome,
      PunchDirection direction, Instant at, String atLocal, List<PunchFlag> flags,
      boolean duplicate, String todayIn, String todayOut, PunchDirection nextDirection,
      List<StoredPunch> stored, List<DerivedDay> derived, List<Instant> lastPunch,
      List<String> results) {}

  /** The stored row minus nothing: every field a replay or a register would read. */
  record StoredPunch(String id, String shopId, String employeeId, Instant at, String localDate,
      PunchDirection direction, List<PunchFlag> flags, Integer distance, Integer accuracy,
      String dedupeKey, String payloadSha256, Instant phoneTime, String deviceModel,
      Instant createdAt) {
    static StoredPunch of(Punch p) {
      return new StoredPunch(p.getId(), p.getShopId(), p.getEmployeeId(), p.getAt(),
          p.getLocalDate(), p.getDirection(), List.copyOf(p.getFlags()), p.getDistanceMetres(),
          p.getAccuracyMetres(), p.getDedupeKey(), p.getPayloadSha256(), p.getPhoneTime(),
          p.getDeviceModel(), p.getCreatedAt());
    }
  }

  /** The derived day as written: counted punches, times and source. */
  record DerivedDay(List<String> counted, String inTime, String outTime, Integer workedMinutes,
      int punchCount, boolean missingCheckOut, PunchSource primarySource) {
    static DerivedDay of(PunchDayDeriver.Derived d) {
      return new DerivedDay(d.counted().stream().map(Punch::getDedupeKey).toList(), d.inTime(),
          d.outTime(), d.workedMinutes(), d.punchCount(), d.missingCheckOut(), d.primarySource());
    }
  }

  private static Run run(Harness h, PunchFields fields) throws Exception {
    AppPunchRequest request = h.signed(fields);
    try {
      AppPunchResponse r = h.service.punch(h.pairing, request);
      return new Run(null, null, r.getOutcome(), r.getDirection(), r.getAt(), r.getAtLocal(),
          r.getFlags() == null ? List.of() : List.copyOf(r.getFlags()), r.isDuplicate(),
          r.getTodayIn(), r.getTodayOut(), r.getNextDirection(), stored(h), derived(h),
          List.copyOf(h.lastPunchRecorded), List.copyOf(h.results));
    } catch (BaseException e) {
      return new Run(e.getErrorCode(), e.getMessage(), null, null, null, null, null, false, null,
          null, null, stored(h), derived(h), List.copyOf(h.lastPunchRecorded),
          List.copyOf(h.results));
    }
  }

  private static List<StoredPunch> stored(Harness h) {
    return h.stored.stream().map(StoredPunch::of).toList();
  }

  private static List<DerivedDay> derived(Harness h) {
    return h.derivedWrites.stream().map(DerivedDay::of).toList();
  }

  /**
   * Sets every non-static field of {@code bean} that could hold the fake port; returns how many
   * were set. The pin above says zero, and the property asserts it on the live objects too.
   */
  private static int wire(Object bean, WorkerAccounts accounts) throws Exception {
    int set = 0;
    for (Field f : bean.getClass().getDeclaredFields()) {
      if (!Modifier.isStatic(f.getModifiers()) && f.getType().isAssignableFrom(accounts.getClass())
          && f.getType() != Object.class) {
        f.setAccessible(true);
        f.set(bean, accounts);
        set++;
      }
    }
    return set;
  }

  private static String newPunchId() {
    return UUID.randomUUID().toString().toLowerCase();
  }

  /** The account port as the variant world would answer it, counting every call made. */
  static final class CountingAccounts implements WorkerAccounts {
    final AtomicInteger calls = new AtomicInteger();
    private final AccountState state;

    CountingAccounts(AccountState state) {
      this.state = state;
    }

    @Override
    public Optional<AccountView> account(String userId) {
      calls.incrementAndGet();
      return state.view().filter(v -> v.userId().equals(userId));
    }

    @Override
    public List<OwnedShop> shopsOwnedBy(String ownerEmail) {
      calls.incrementAndGet();
      return List.of();
    }

    @Override
    public boolean claimShop(String shopId, String userId) {
      calls.incrementAndGet();
      return false;
    }

    @Override
    public boolean releaseShop(String shopId, String userId) {
      calls.incrementAndGet();
      return false;
    }

    @Override
    public void addWorkerMembership(String shopId, String userId) {
      calls.incrementAndGet();
    }

    @Override
    public boolean hasPhoto(String userId) {
      calls.incrementAndGet();
      return false;
    }

    @Override
    public AccountView updateProfile(String userId, String name, String phone) {
      calls.incrementAndGet();
      return state.view().orElseThrow();
    }
  }
}
