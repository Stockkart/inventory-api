// Feature: HR-module, Property 10: Rate limiters are bounded
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.service.AppRateLimiterTest.MutableClock;
import com.inventory.metrics.MetricsWrapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * The Attendance app's limits are bounded (design Property 10): for any sequence of request times
 * on one key, the sliding window admits at most {@code limit} requests in any interval of length
 * {@code window} and refuses only when the window already holds {@code limit} admitted ones; keys
 * are independent; the 5th refused code attempt within 15 minutes locks the installation for
 * exactly 15 minutes, the time left never grows until the lock ends, the message's minutes are the
 * time left rounded up (never 0) and the count starts from zero after the lock; and {@link
 * AppRateLimiter#sweep()} never changes an answer.
 *
 * <p>Each property drives the limiter and an independent reference model with the same inputs
 * and compares the answers step by step.
 *
 * <p><b>Validates: Requirements 2.5, 9.9, 9.10</b>
 */
class AppRateLimiterProperties {

  private static final Instant T0 = Instant.parse("2025-04-03T09:00:00Z");
  private static final Pattern MINUTES = Pattern.compile("Wait (\\d+) minutes?");

  /** One request: wait {@code gapMillis}, optionally sweep, then try key {@code key}. */
  record Step(int key, long gapMillis, boolean sweepFirst) {}

  private static AppRateLimiter limiter(MutableClock clock) {
    return new AppRateLimiter(new MetricsWrapper(new SimpleMeterRegistry()), clock);
  }

  /** The reference: admitted instants per key, pruned to {@code (now − window, now]}. */
  static final class Model {
    private final Map<Integer, ArrayDeque<Instant>> admitted = new HashMap<>();

    int inWindow(int key, Instant now, Duration window) {
      ArrayDeque<Instant> q = admitted.computeIfAbsent(key, k -> new ArrayDeque<>());
      Instant cutoff = now.minus(window);
      while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
        q.pollFirst();
      }
      return q.size();
    }

    boolean tryAcquire(int key, Instant now, int limit, Duration window) {
      if (inWindow(key, now, window) >= limit) {
        return false;
      }
      admitted.get(key).addLast(now);
      return true;
    }
  }

  // ---- sliding window --------------------------------------------------------------------------

  @Provide
  Arbitrary<List<Step>> punchSteps() {
    // gaps of 0–8 s on up to three pairings: 20 in 60 s is reached often and slides often
    Arbitrary<Step> step = Combinators.combine(
        Arbitraries.integers().between(0, 2),
        Arbitraries.longs().between(0, 8_000),
        Arbitraries.frequencyOf(
            Tuple.of(4, Arbitraries.just(false)),
            Tuple.of(1, Arbitraries.just(true))))
        .as(Step::new);
    return step.list().ofMinSize(1).ofMaxSize(150);
  }

  @Property(tries = 200)
  void punchesAreBoundedToTwentyPerMinutePerPairingAndSweepChangesNothing(
      @ForAll("punchSteps") List<Step> steps) {
    MutableClock clock = new MutableClock(T0);
    AppRateLimiter swept = limiter(clock);
    AppRateLimiter untouched = limiter(clock);
    Model model = new Model();
    Map<Integer, List<Instant>> acceptedByKey = new HashMap<>();

    for (Step s : steps) {
      clock.advance(Duration.ofMillis(s.gapMillis()));
      Instant now = clock.instant();
      if (s.sweepFirst()) {
        swept.sweep();
      }
      int before = model.inWindow(s.key(), now, AppRateLimiter.PUNCH_WINDOW);
      boolean expected = model.tryAcquire(s.key(), now, AppRateLimiter.PUNCH_LIMIT,
          AppRateLimiter.PUNCH_WINDOW);

      boolean actual = swept.tryAcquirePunch("pairing-" + s.key());
      assertEquals(expected, actual, "answer at " + now + " for key " + s.key());
      assertEquals(actual, untouched.tryAcquirePunch("pairing-" + s.key()),
          "sweep() must not change the answer");
      if (!actual) {
        assertEquals(AppRateLimiter.PUNCH_LIMIT, before,
            "a refusal is only allowed when the window already holds the limit");
      } else {
        acceptedByKey.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(now);
      }
    }
    // in any interval of 60 s, at most 20 were admitted per key
    for (List<Instant> accepted : acceptedByKey.values()) {
      for (Instant end : accepted) {
        Instant start = end.minus(AppRateLimiter.PUNCH_WINDOW);
        long inWindow = accepted.stream().filter(t -> t.isAfter(start) && !t.isAfter(end)).count();
        assertTrue(inWindow <= AppRateLimiter.PUNCH_LIMIT,
            inWindow + " admitted in the 60 s ending at " + end);
      }
    }
  }

  @Provide
  Arbitrary<List<Step>> genericSteps() {
    Arbitrary<Step> step = Combinators.combine(
        Arbitraries.integers().between(0, 1),
        Arbitraries.longs().between(0, 4_000),
        Arbitraries.of(false, false, false, true))
        .as(Step::new);
    return step.list().ofMinSize(1).ofMaxSize(100);
  }

  @Property(tries = 200)
  void anyLimitAndWindowFollowTheSlidingWindowRule(@ForAll("genericSteps") List<Step> steps,
      @ForAll("limits") int limit, @ForAll("windowSeconds") int windowSeconds) {
    Duration window = Duration.ofSeconds(windowSeconds);
    MutableClock clock = new MutableClock(T0);
    AppRateLimiter limiter = limiter(clock);
    Model model = new Model();

    for (Step s : steps) {
      clock.advance(Duration.ofMillis(s.gapMillis()));
      Instant now = clock.instant();
      if (s.sweepFirst()) {
        limiter.sweep();
      }
      int before = model.inWindow(s.key(), now, window);
      boolean expected = model.tryAcquire(s.key(), now, limit, window);
      boolean actual = limiter.tryAcquire("k" + s.key(), limit, window);
      assertEquals(expected, actual, "answer at " + now + " for key " + s.key());
      assertTrue(actual || before == limit, "refused with only " + before + " in the window");
    }
  }

  @Provide
  Arbitrary<Integer> limits() {
    return Arbitraries.integers().between(1, 6);
  }

  @Provide
  Arbitrary<Integer> windowSeconds() {
    return Arbitraries.integers().between(1, 20);
  }

  // ---- code lockout ----------------------------------------------------------------------------

  /** One code attempt: wait {@code gapMillis}, then a refused ({@code wrong}) or a good attempt. */
  record Attempt(long gapMillis, boolean wrong) {}

  @Provide
  Arbitrary<List<Attempt>> attempts() {
    // mostly short gaps so five refusals cluster; some long ones so locks and windows expire
    Arbitrary<Long> gap = Arbitraries.frequencyOf(
        Tuple.of(6, Arbitraries.longs().between(0, 4 * 60_000L)),
        Tuple.of(1, Arbitraries.longs().between(4 * 60_000L, 16 * 60_000L)));
    Arbitrary<Attempt> attempt = Combinators.combine(gap, Arbitraries.of(true, true, true, false))
        .as(Attempt::new);
    return attempt.list().ofMinSize(1).ofMaxSize(60);
  }

  @Property(tries = 200)
  void theFifthRefusalInFifteenMinutesLocksForExactlyFifteenMinutesAndTheMessageSaysHowLong(
      @ForAll("attempts") List<Attempt> attempts) {
    MutableClock clock = new MutableClock(T0);
    AppRateLimiter limiter = limiter(clock);
    String install = "install-1";
    String other = "install-2";

    // reference model
    ArrayDeque<Instant> refusals = new ArrayDeque<>();
    Instant lockedUntil = Instant.MIN;
    Duration previousRemaining = null;

    for (Attempt a : attempts) {
      clock.advance(Duration.ofMillis(a.gapMillis()));
      Instant now = clock.instant();
      Duration expectedRemaining = now.isBefore(lockedUntil)
          ? Duration.between(now, lockedUntil) : Duration.ZERO;
      Duration remaining = limiter.codeLockRemaining(install);
      assertEquals(expectedRemaining, remaining, "time left at " + now);
      assertEquals(Duration.ZERO, limiter.codeLockRemaining(other),
          "another installation is never affected");
      if (previousRemaining != null && !remaining.isZero()) {
        assertTrue(remaining.compareTo(previousRemaining) <= 0,
            "time left grew from " + previousRemaining + " to " + remaining);
      }

      AtomicInteger calls = new AtomicInteger();
      if (!remaining.isZero()) {
        BaseException e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(install,
            () -> {
              calls.incrementAndGet();
              throw new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG, "wrong");
            }));
        assertEquals(ErrorCode.HR_PAIRING_CODE_LOCKED, e.getErrorCode());
        assertEquals(0, calls.get(), "the attempt never runs while locked");
        long expectedMinutes = Math.ceilDiv(remaining.toMillis(), 60_000L);
        assertTrue(expectedMinutes >= 1);
        assertEquals(expectedMinutes, minutesIn(e.getMessage()), e.getMessage());
        previousRemaining = remaining;
        continue;
      }
      previousRemaining = null;

      if (a.wrong()) {
        BaseException e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(install,
            () -> {
              calls.incrementAndGet();
              throw new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG, "wrong");
            }));
        assertEquals(ErrorCode.HR_PAIRING_CODE_WRONG, e.getErrorCode(), "the refusal passes through");
        assertEquals(1, calls.get());
        Instant cutoff = now.minus(AppRateLimiter.CODE_FAILURE_WINDOW);
        while (!refusals.isEmpty() && !refusals.peekFirst().isAfter(cutoff)) {
          refusals.pollFirst();
        }
        refusals.addLast(now);
        if (refusals.size() >= AppRateLimiter.CODE_FAILURE_LIMIT) {
          lockedUntil = now.plus(AppRateLimiter.CODE_LOCK);
          refusals.clear();
          assertEquals(AppRateLimiter.CODE_LOCK, limiter.codeLockRemaining(install),
              "the 5th refusal locks for exactly 15 minutes");
          previousRemaining = AppRateLimiter.CODE_LOCK;
        } else {
          assertEquals(Duration.ZERO, limiter.codeLockRemaining(install),
              refusals.size() + " refusals do not lock");
        }
      } else {
        assertEquals("ok", limiter.guardCodeAttempt(install, () -> {
          calls.incrementAndGet();
          return "ok";
        }));
        assertEquals(1, calls.get());
        assertEquals(Duration.ZERO, limiter.codeLockRemaining(install),
            "a good attempt never locks");
      }
    }
  }

  @Property(tries = 100)
  void afterTheLockEndsTheCountStartsFromZero(@ForAll("refusalsBeforeLock") int earlier,
      @ForAll("afterLock") long afterLockMillis, @ForAll("smallGap") long gapMillis) {
    MutableClock clock = new MutableClock(T0);
    AppRateLimiter limiter = limiter(clock);
    String install = "install-1";

    // some refusals, then the ones that lock
    for (int i = 0; i < earlier; i++) {
      limiter.recordCodeRefusal(install);
      clock.advance(Duration.ofMillis(gapMillis));
    }
    while (limiter.codeLockRemaining(install).isZero()) {
      limiter.recordCodeRefusal(install);
    }
    assertEquals(AppRateLimiter.CODE_LOCK, limiter.codeLockRemaining(install));
    // refusals while locked do not count either
    limiter.recordCodeRefusal(install);
    limiter.recordCodeRefusal(install);

    clock.advance(AppRateLimiter.CODE_LOCK.plusMillis(afterLockMillis));
    assertEquals(Duration.ZERO, limiter.codeLockRemaining(install), "the lock has ended");
    for (int i = 0; i < AppRateLimiter.CODE_FAILURE_LIMIT - 1; i++) {
      assertEquals(Duration.ZERO, limiter.recordCodeRefusal(install), "refusal " + (i + 1));
      clock.advance(Duration.ofMillis(gapMillis));
    }
    assertEquals(AppRateLimiter.CODE_LOCK, limiter.recordCodeRefusal(install),
        "the 5th after the lock locks again");
    assertFalse(limiter.codeLockRemaining(install).isZero());
  }

  @Provide
  Arbitrary<Integer> refusalsBeforeLock() {
    return Arbitraries.integers().between(0, 4);
  }

  @Provide
  Arbitrary<Long> afterLock() {
    return Arbitraries.longs().between(0, 60 * 60_000L);
  }

  @Provide
  Arbitrary<Long> smallGap() {
    // four refusals plus gaps must stay inside the 15-minute window
    return Arbitraries.longs().between(0, 3 * 60_000L);
  }

  private static long minutesIn(String message) {
    Matcher m = MINUTES.matcher(message);
    assertTrue(m.find(), "no minutes in: " + message);
    return Long.parseLong(m.group(1));
  }
}
