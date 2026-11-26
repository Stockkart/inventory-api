// Feature: worker-app, Property 28: Rate limits are bounded and refusals are side-effect free
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.api.port.ShopNamePort;
import com.inventory.hr.domain.model.WorkerJoinRequest;
import com.inventory.hr.domain.model.enums.EmployeeStatus;
import com.inventory.hr.domain.model.enums.HrNotificationType;
import com.inventory.hr.domain.model.enums.JoinOutcome;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.WorkerJoinRequestRepository;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.service.api.WorkerAppAuthPortImpl;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.WorkerAccounts;
import com.inventory.user.api.WorkerAccounts.AccountView;
import com.inventory.user.api.WorkerAccounts.OwnedShop;
import com.inventory.user.api.port.WorkerAppAuthPort;
import com.inventory.user.domain.model.UserRole;
import com.inventory.user.domain.repository.UserAccountRepository;
import com.inventory.user.rest.dto.request.AuthSource;
import com.inventory.user.rest.dto.request.SignupRequest;
import com.inventory.user.service.AuthService;
import com.inventory.user.validation.AuthValidator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Worker-app design Property 28, the three worker limits of R3.8 and R14.1: shop lookups
 * ({@code lookup:<userId>}, 10 an hour), join requests ({@code join:<userId>}, 5 in 24 hours) and
 * app sign-ups ({@code signup-ip:<ip>}, 20 an hour).
 *
 * <p>Two halves. <b>Bounded</b>: for any sequence of calls with random keys and gaps over a fake
 * clock, driven through the real entry points ({@link WorkerJoinService#lookupShops}, {@link
 * WorkerJoinService#create}, {@link WorkerAppAuthPortImpl#admitSignup}), the admitted count in any
 * interval of the window's length never exceeds the limit, a call is admitted whenever fewer than
 * the limit were admitted in the preceding window (so the window slides: calls older than the
 * window free a slot), and keys are independent. An independent reference model is driven with the
 * same inputs and must agree call by call. <b>Side-effect free</b>: a refused lookup, join or
 * sign-up answers {@code HR_TOO_MANY_ATTEMPTS} with the text "Too many attempts, try again later"
 * and reaches nothing behind the gate: no shop lookup, no request row, no approver notification,
 * no account, no validator call and none of the worker counters move; the next admitted call does
 * all of that exactly once.
 *
 * <p>The lookup and join paths run the real {@link WorkerJoinService} over in-memory fakes of the
 * account port and the request store, with the real {@link HrWorkerCounters} on a {@link
 * SimpleMeterRegistry}; the sign-up path runs the real {@link AuthService} gate over the real
 * {@link WorkerAppAuthPortImpl} with the validator replaced by a sentinel that marks "the gate was
 * passed", so no account machinery is needed to tell an admitted call from a refused one.
 *
 * <p><b>Validates: Requirements 3.8, 14.1</b>
 */
class AppRateLimiterWorkerProperties {

  private static final Instant T0 = Instant.parse("2026-05-12T09:00:00Z");
  private static final String REFUSAL_TEXT = "Too many attempts, try again later";
  private static final String SHOP = "5f1a2b3c4d5e6f7a8b9c0d1e";
  private static final List<String> USERS = List.of("acct-0", "acct-1", "acct-2");
  private static final List<String> IPS = List.of("203.0.113.9", "203.0.113.10", "198.51.100.7");

  /** One call: wait {@code gapSeconds}, then call for key number {@code key}. */
  record Step(int key, long gapSeconds) {}

  /** The three limits of R14.1 as the design table names them. */
  enum Path {
    LOOKUP(WorkerJoinService.LOOKUP_LIMIT, WorkerJoinService.LOOKUP_WINDOW),
    JOIN(WorkerJoinService.JOIN_LIMIT, WorkerJoinService.JOIN_WINDOW),
    SIGNUP(WorkerAppAuthPort.SIGNUP_IP_LIMIT, Duration.ofHours(1));

    final int limit;
    final Duration window;

    Path(int limit, Duration window) {
      this.limit = limit;
      this.window = window;
    }
  }

  // ---- the limits of the design table are the ones the code holds -------------------------------

  @Example
  void theLimitsAreTheOnesOfTheDesignTable() {
    assertEquals(10, WorkerJoinService.LOOKUP_LIMIT);
    assertEquals(Duration.ofHours(1), WorkerJoinService.LOOKUP_WINDOW);
    assertEquals(5, WorkerJoinService.JOIN_LIMIT);
    assertEquals(Duration.ofHours(24), WorkerJoinService.JOIN_WINDOW);
    assertEquals(20, WorkerAppAuthPort.SIGNUP_IP_LIMIT);
    assertEquals(REFUSAL_TEXT, ErrorCode.HR_TOO_MANY_ATTEMPTS_WORKER_MESSAGE);
    assertEquals(429, ErrorCode.HR_TOO_MANY_ATTEMPTS.getHttpStatus().value());
  }

  // ---- bounded and sliding, through the real entry points --------------------------------------

  @Property(tries = 150)
  void everyWorkerLimitIsBoundedSlidesAndKeepsKeysApart(@ForAll Path path,
      @ForAll("steps") List<Step> rawSteps) {
    List<Step> steps = scaled(path, rawSteps);
    FakeClock clock = new FakeClock(T0);
    Fixture f = new Fixture(clock);
    Model model = new Model(path.limit, path.window);
    Map<Integer, List<Instant>> admittedByKey = new HashMap<>();

    for (Step s : steps) {
      clock.advance(Duration.ofSeconds(s.gapSeconds()));
      Instant now = clock.instant();
      int inWindowBefore = model.inWindow(s.key(), now);
      boolean expected = model.tryAcquire(s.key(), now);

      boolean actual = f.call(path, s.key());

      assertEquals(expected, actual, path + " at " + now + " for key " + s.key()
          + " with " + inWindowBefore + " admitted in the window");
      if (actual) {
        admittedByKey.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(now);
      } else {
        assertEquals(path.limit, inWindowBefore,
            path + ": a refusal is allowed only when the window already holds the limit");
      }
    }
    // in any interval of the window's length, at most `limit` were admitted per key
    for (List<Instant> admitted : admittedByKey.values()) {
      for (Instant end : admitted) {
        Instant start = end.minus(path.window);
        long inWindow = admitted.stream().filter(t -> t.isAfter(start) && !t.isAfter(end)).count();
        assertTrue(inWindow <= path.limit,
            path + ": " + inWindow + " admitted in the window ending at " + end);
      }
    }
  }

  /**
   * After the limit is reached, the slot comes back exactly when the oldest admitted call leaves
   * the window, not a second earlier: the window slides per call (R3.8 "per hour", "per 24
   * hours" are rolling).
   */
  @Property(tries = 60)
  void aSlotFreesExactlyWhenTheOldestAdmittedCallLeavesTheWindow(@ForAll Path path,
      @ForAll("spreads") List<Long> spreadSeconds) {
    FakeClock clock = new FakeClock(T0);
    Fixture f = new Fixture(clock);
    // fill the window with `limit` admitted calls spread inside it; the first one is at T0 and
    // every later one strictly after it, so exactly one call leaves the window at a time
    List<Instant> admitted = new ArrayList<>();
    long budget = path.window.toSeconds() - 1;
    long maxGap = Math.max(1, budget / path.limit - 1);
    for (int i = 0; i < path.limit; i++) {
      if (i > 0) {
        long gap = 1 + spreadSeconds.get(i % spreadSeconds.size()) % maxGap;
        clock.advance(Duration.ofSeconds(gap));
      }
      assertTrue(f.call(path, 0), path + ": call " + (i + 1) + " of " + path.limit);
      admitted.add(clock.instant());
    }
    assertFalse(f.call(path, 0), path + ": the one over the limit is refused");

    // one second before the oldest leaves the window: still full
    Instant oldest = admitted.get(0);
    Instant frees = oldest.plus(path.window);
    clock.set(frees.minusSeconds(1));
    assertFalse(f.call(path, 0), path + ": still full one second before the oldest leaves");
    // the moment it leaves (the window is (now − window, now]): one slot, and one only
    clock.set(frees);
    assertTrue(f.call(path, 0), path + ": the oldest left the window, one slot is free");
    assertFalse(f.call(path, 0), path + ": exactly one slot came back");
  }

  // ---- refusals are side-effect free ---------------------------------------------------------

  @Property(tries = 100)
  void aRefusedLookupJoinOrSignupReachesNothingAndCountsNothing(@ForAll Path path,
      @ForAll("overshoot") int overshoot, @ForAll("smallGaps") List<Long> gaps) {
    FakeClock clock = new FakeClock(T0);
    Fixture f = new Fixture(clock);
    int key = 0;
    // up to the limit: every call is admitted and reaches the service exactly once
    for (int i = 0; i < path.limit; i++) {
      clock.advance(Duration.ofSeconds(gaps.get(i % gaps.size())));
      Effects before = f.effects();
      assertTrue(f.call(path, key), path + ": call " + (i + 1));
      Effects after = f.effects();
      assertEquals(before.plusOne(path), after,
          path + ": an admitted call does its work exactly once");
    }
    // beyond it: refused with the worker text, and nothing behind the gate moves
    Effects frozen = f.effects();
    for (int i = 0; i < overshoot; i++) {
      clock.advance(Duration.ofSeconds(gaps.get(i % gaps.size())));
      BaseException e = assertThrows(BaseException.class, () -> f.invoke(path, key),
          path + ": over the limit must be refused");
      assertEquals(ErrorCode.HR_TOO_MANY_ATTEMPTS, e.getErrorCode());
      assertEquals(REFUSAL_TEXT, e.getMessage(), path + ": the worker text, not the punch one");
      assertEquals(frozen, f.effects(), path + ": a refusal is side-effect free");
    }
    // another key is untouched by all of this
    Effects before = f.effects();
    assertTrue(f.call(path, 1), path + ": another key has its own window");
    assertEquals(before.plusOne(path), f.effects());
  }

  // ---- generators ----------------------------------------------------------------------------

  /** Random keys (three per path) and gaps in "window units", scaled per path by {@link #scaled}. */
  @Provide
  Arbitrary<List<Step>> steps() {
    Arbitrary<Step> step = Combinators.combine(
        Arbitraries.integers().between(0, 2),
        // gaps mostly short, so the limit is reached often; sometimes long, so the window slides
        Arbitraries.frequencyOf(
            Tuple.of(6, Arbitraries.longs().between(0, 60)),
            Tuple.of(2, Arbitraries.longs().between(60, 600)),
            Tuple.of(1, Arbitraries.longs().between(600, 1300))))
        .as(Step::new);
    return step.list().ofMinSize(1).ofMaxSize(120);
  }

  /** The raw gaps are in units of 1/1000 of the window, so each path sees the same shape. */
  private static List<Step> scaled(Path path, List<Step> raw) {
    long unit = Math.max(1, path.window.toSeconds() / 1000);
    List<Step> out = new ArrayList<>(raw.size());
    for (Step s : raw) {
      out.add(new Step(s.key(), s.gapSeconds() * unit));
    }
    return out;
  }

  @Provide
  Arbitrary<List<Long>> spreads() {
    return Arbitraries.longs().between(0, 24 * 3600L).list().ofMinSize(1).ofMaxSize(20);
  }

  @Provide
  Arbitrary<Integer> overshoot() {
    return Arbitraries.integers().between(1, 8);
  }

  @Provide
  Arbitrary<List<Long>> smallGaps() {
    // all inside the smallest window (an hour): 25 calls × ≤ 120 s < 3600 s
    return Arbitraries.longs().between(0, 120).list().ofMinSize(1).ofMaxSize(10);
  }

  // ---- reference model -------------------------------------------------------------------------

  /** Admitted instants per key, pruned to {@code (now − window, now]}. */
  static final class Model {
    private final int limit;
    private final Duration window;
    private final Map<Integer, ArrayDeque<Instant>> admitted = new HashMap<>();

    Model(int limit, Duration window) {
      this.limit = limit;
      this.window = window;
    }

    int inWindow(int key, Instant now) {
      ArrayDeque<Instant> q = admitted.computeIfAbsent(key, k -> new ArrayDeque<>());
      Instant cutoff = now.minus(window);
      while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
        q.pollFirst();
      }
      return q.size();
    }

    boolean tryAcquire(int key, Instant now) {
      if (inWindow(key, now) >= limit) {
        return false;
      }
      admitted.get(key).addLast(now);
      return true;
    }
  }

  // ---- the fixture: real services over fakes ---------------------------------------------------

  /** Everything observable behind the three gates, as counts. */
  record Effects(int shopLookups, int lookupsCounted, int requestRows, int approverNotices,
      int joinsCounted, int validatorCalls, int accountsSaved, int signupsCounted) {

    /** What one admitted call on {@code path} must add, and nothing else. */
    Effects plusOne(Path path) {
      return switch (path) {
        case LOOKUP -> new Effects(shopLookups + 1, lookupsCounted + 1, requestRows,
            approverNotices, joinsCounted, validatorCalls, accountsSaved, signupsCounted);
        case JOIN -> new Effects(shopLookups, lookupsCounted, requestRows + 1,
            approverNotices + 1, joinsCounted + 1, validatorCalls, accountsSaved, signupsCounted);
        // an admitted sign-up reaches the validator (the sentinel); the account itself is never
        // made here, and worker_signups_total counts made accounts only
        case SIGNUP -> new Effects(shopLookups, lookupsCounted, requestRows, approverNotices,
            joinsCounted, validatorCalls + 1, accountsSaved, signupsCounted);
      };
    }
  }

  /** The sentinel the sign-up validator throws once the gate was passed. */
  static final class GatePassed extends ValidationException {
    GatePassed() {
      super("gate passed");
    }
  }

  static final class Fixture {
    final MeterRegistry registry = new SimpleMeterRegistry();
    final AppRateLimiter limiter;
    final WorkerJoinService joins;
    final AuthService auth;
    final AtomicInteger shopLookups = new AtomicInteger();
    final AtomicInteger approverNotices = new AtomicInteger();
    final AtomicInteger validatorCalls = new AtomicInteger();
    final AtomicInteger accountsSaved = new AtomicInteger();
    final List<WorkerJoinRequest> requests = new ArrayList<>();

    Fixture(FakeClock clock) {
      MetricsWrapper metrics = new MetricsWrapper(registry);
      limiter = new AppRateLimiter(metrics, clock);
      HrWorkerCounters counters = new HrWorkerCounters(metrics);

      // ---- lookup and join: the real WorkerJoinService over in-memory fakes
      WorkerAccounts accounts = mock(WorkerAccounts.class);
      when(accounts.shopsOwnedBy(anyString())).thenAnswer(inv -> {
        shopLookups.incrementAndGet();
        return List.of(new OwnedShop(SHOP, "Sri Ganesh Stores", "Ramesh Kumar"));
      });
      when(accounts.account(anyString())).thenAnswer(inv -> Optional.of(new AccountView(
          inv.getArgument(0), "Priya Sharma", inv.getArgument(0) + "@example.com", "9876543210",
          UserRole.WORKER.name(), null, true)));
      when(accounts.hasPhoto(anyString())).thenReturn(false);

      EmployeeRepository employees = mock(EmployeeRepository.class);
      when(employees.findByUserIdAndStatus(anyString(), any(EmployeeStatus.class)))
          .thenReturn(List.of());

      WorkerJoinRequestRepository requestRepository = mock(WorkerJoinRequestRepository.class);
      when(requestRepository.findFirstByUserIdAndStatusIn(anyString(), anyCollection()))
          .thenAnswer(inv -> {
            String userId = inv.getArgument(0);
            Collection<WorkerJoinStatus> statuses = inv.getArgument(1);
            return requests.stream().filter(r -> userId.equals(r.getUserId())
                && statuses.contains(r.getStatus())).findFirst();
          });
      when(requestRepository.insert(any(WorkerJoinRequest.class))).thenAnswer(inv -> {
        WorkerJoinRequest r = inv.getArgument(0);
        r.setId(String.format("req-%03d", requests.size() + 1));
        requests.add(r);
        return r;
      });

      HrNotificationService notifications = mock(HrNotificationService.class);
      Mockito.doAnswer(inv -> {
        approverNotices.incrementAndGet();
        return null;
      }).when(notifications).notifyApprovers(anyString(), eq(HrNotificationType.JOIN_REQUEST_RECEIVED),
          anyString(), anyString());

      ShopNamePort shopNames = mock(ShopNamePort.class);
      when(shopNames.getShopName(anyString())).thenReturn("Sri Ganesh Stores");

      joins = new WorkerJoinService();
      ReflectionTestUtils.setField(joins, "workerAccounts", accounts);
      ReflectionTestUtils.setField(joins, "employeeRepository", employees);
      ReflectionTestUtils.setField(joins, "requestRepository", requestRepository);
      ReflectionTestUtils.setField(joins, "notificationService", notifications);
      ReflectionTestUtils.setField(joins, "shopNamePort", shopNames);
      ReflectionTestUtils.setField(joins, "rateLimiter", limiter);
      ReflectionTestUtils.setField(joins, "counters", counters);

      // ---- sign-up: the real AuthService gate over the real port; the validator is the sentinel
      AuthValidator validator = mock(AuthValidator.class);
      Mockito.doAnswer(inv -> {
        validatorCalls.incrementAndGet();
        throw new GatePassed();
      }).when(validator).validateSignupRequest(any());
      UserAccountRepository users = mock(UserAccountRepository.class);
      when(users.save(any())).thenAnswer(inv -> {
        accountsSaved.incrementAndGet();
        return inv.getArgument(0);
      });
      auth = new AuthService();
      ReflectionTestUtils.setField(auth, "authValidator", validator);
      ReflectionTestUtils.setField(auth, "userAccountRepository", users);
      ReflectionTestUtils.setField(auth, "metrics", metrics);
      ReflectionTestUtils.setField(auth, "workerAppAuth",
          new WorkerAppAuthPortImpl(limiter, counters));
    }

    /** Runs one call; true when admitted, false when refused by the limit. */
    boolean call(Path path, int key) {
      try {
        invoke(path, key);
        return true;
      } catch (GatePassed passed) {
        return true;
      } catch (BaseException e) {
        if (e.getErrorCode() == ErrorCode.HR_TOO_MANY_ATTEMPTS) {
          assertEquals(REFUSAL_TEXT, e.getMessage(), path + ": the worker text");
          return false;
        }
        throw e;
      }
    }

    /** The real call for the path; a refusal propagates as {@code HR_TOO_MANY_ATTEMPTS}. */
    void invoke(Path path, int key) {
      switch (path) {
        case LOOKUP -> {
          ShopLookupResponse r = joins.lookupShops(USERS.get(key), "owner@example.com");
          assertEquals(1, r.getShops().size());
        }
        case JOIN -> {
          String userId = USERS.get(key);
          joins.create(userId, SHOP);
          // the one-open-request rule (R3.5) would otherwise stop the next create before the
          // limiter is reached: the worker withdraws it, as the app allows (R3.6)
          for (WorkerJoinRequest r : requests) {
            if (userId.equals(r.getUserId()) && r.getStatus() == WorkerJoinStatus.PENDING) {
              r.setStatus(WorkerJoinStatus.WITHDRAWN);
              r.setOutcome(JoinOutcome.WITHDRAWN);
            }
          }
        }
        case SIGNUP -> {
          SignupRequest request = new SignupRequest();
          request.setName("Priya Sharma");
          request.setEmail("priya" + key + "@example.com");
          request.setPassword("correct-horse-battery");
          request.setPhone("9876543210");
          request.setRole(UserRole.WORKER);
          request.setSource(AuthSource.WORKER_APP);
          auth.signup(request, IPS.get(key));
        }
      }
    }

    Effects effects() {
      return new Effects(shopLookups.get(), (int) count(HrMetricsConstants.WORKER_LOOKUPS_TOTAL),
          requests.size(), approverNotices.get(),
          (int) count(HrMetricsConstants.WORKER_JOIN_REQUESTS_TOTAL), validatorCalls.get(),
          accountsSaved.get(), (int) count(HrMetricsConstants.WORKER_SIGNUPS_TOTAL));
    }

    private double count(String meter) {
      double total = 0;
      for (Counter c : registry.find(meter).counters()) {
        total += c.count();
      }
      return total;
    }
  }

  /** A clock the property moves by hand. */
  static final class FakeClock extends Clock {
    private Instant now;

    FakeClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    void set(Instant at) {
      now = at;
    }

    @Override
    public ZoneOffset getZone() {
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
}
