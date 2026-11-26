package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppRateLimiterTest {

  private static final Instant T0 = Instant.parse("2025-04-03T09:00:00Z");
  private static final String INSTALL = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

  @Mock private MetricsWrapper metrics;

  private MutableClock clock;
  private AppRateLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(T0);
    limiter = new AppRateLimiter(metrics, clock);
  }

  // ---- sliding window --------------------------------------------------------------------------

  @Test
  void admitsUpToTheLimitThenRefusesUntilTheWindowSlides() {
    for (int i = 0; i < 20; i++) {
      assertTrue(limiter.tryAcquirePunch("p1"), "punch " + (i + 1));
    }
    assertFalse(limiter.tryAcquirePunch("p1"), "the 21st in the same second");
    clock.advance(Duration.ofSeconds(59));
    assertFalse(limiter.tryAcquirePunch("p1"), "still inside the 60 s window");
    clock.advance(Duration.ofSeconds(1));
    assertTrue(limiter.tryAcquirePunch("p1"), "the first 20 have slid out");
  }

  @Test
  void refusedRequestsDoNotConsumeTheWindow() {
    for (int i = 0; i < 3; i++) {
      assertTrue(limiter.tryAcquire("k", 3, Duration.ofSeconds(10)));
    }
    // hammer while refused: none of these may push the window forward
    for (int i = 0; i < 50; i++) {
      clock.advance(Duration.ofMillis(100));
      assertFalse(limiter.tryAcquire("k", 3, Duration.ofSeconds(10)));
    }
    clock.advance(Duration.ofSeconds(5)); // 10 s after the three admitted ones
    assertTrue(limiter.tryAcquire("k", 3, Duration.ofSeconds(10)));
  }

  @Test
  void theWindowSlidesPerRequestNotPerBatch() {
    assertTrue(limiter.tryAcquire("k", 2, Duration.ofSeconds(10)));
    clock.advance(Duration.ofSeconds(6));
    assertTrue(limiter.tryAcquire("k", 2, Duration.ofSeconds(10)));
    assertFalse(limiter.tryAcquire("k", 2, Duration.ofSeconds(10)));
    clock.advance(Duration.ofSeconds(4)); // first one is now exactly 10 s old: out
    assertTrue(limiter.tryAcquire("k", 2, Duration.ofSeconds(10)));
    assertFalse(limiter.tryAcquire("k", 2, Duration.ofSeconds(10)), "second one is 4 s old");
  }

  @Test
  void keysAreIndependent() {
    for (int i = 0; i < 20; i++) {
      limiter.tryAcquirePunch("p1");
    }
    assertFalse(limiter.tryAcquirePunch("p1"));
    assertTrue(limiter.tryAcquirePunch("p2"));
  }

  // ---- code lockout ----------------------------------------------------------------------------

  @Test
  void fourRefusalsDoNotLockTheFifthLocksForFifteenMinutes() {
    for (int i = 0; i < 4; i++) {
      assertEquals(Duration.ZERO, limiter.recordCodeRefusal(INSTALL));
      assertEquals(Duration.ZERO, limiter.codeLockRemaining(INSTALL));
    }
    assertEquals(Duration.ofMinutes(15), limiter.recordCodeRefusal(INSTALL));
    assertEquals(Duration.ofMinutes(15), limiter.codeLockRemaining(INSTALL));
    clock.advance(Duration.ofMinutes(14).plusSeconds(59));
    assertEquals(Duration.ofSeconds(1), limiter.codeLockRemaining(INSTALL));
    clock.advance(Duration.ofSeconds(1));
    assertEquals(Duration.ZERO, limiter.codeLockRemaining(INSTALL));
    // the lock cleared the count: five more are needed again
    for (int i = 0; i < 4; i++) {
      assertEquals(Duration.ZERO, limiter.recordCodeRefusal(INSTALL));
    }
    assertEquals(Duration.ofMinutes(15), limiter.recordCodeRefusal(INSTALL));
  }

  @Test
  void refusalsOlderThanFifteenMinutesDoNotCountTowardsTheLock() {
    for (int i = 0; i < 4; i++) {
      limiter.recordCodeRefusal(INSTALL);
    }
    clock.advance(Duration.ofMinutes(15));
    assertEquals(Duration.ZERO, limiter.recordCodeRefusal(INSTALL), "the four have aged out");
  }

  @Test
  void guardRunsTheAttemptAndPassesItsAnswerThrough() {
    assertEquals("ok", limiter.guardCodeAttempt(INSTALL, () -> "ok"));
    assertEquals(Duration.ZERO, limiter.codeLockRemaining(INSTALL));
  }

  @Test
  void guardCountsOnlyCodeRefusalsAndRethrowsThem() {
    AtomicInteger calls = new AtomicInteger();
    for (ErrorCode code : AppRateLimiter.CODE_REFUSALS) {
      if (calls.get() == 4) {
        break;
      }
      BaseException e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL,
          () -> {
            calls.incrementAndGet();
            throw new BaseException(code, code.getMessage());
          }));
      assertEquals(code, e.getErrorCode(), "the refusal is passed through unchanged");
    }
    // a 400, a 404 or a 500 is not the worker guessing codes
    assertThrows(ValidationException.class, () -> limiter.guardCodeAttempt(INSTALL, () -> {
      throw new ValidationException("installationId is required");
    }));
    assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL, () -> {
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "db");
    }));
    assertEquals(Duration.ZERO, limiter.codeLockRemaining(INSTALL), "four counted, not locked");
    // the fifth code refusal locks
    assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL, () -> {
      throw new BaseException(ErrorCode.HR_PAIRING_CODE_WRONG, "wrong");
    }));
    assertEquals(Duration.ofMinutes(15), limiter.codeLockRemaining(INSTALL));
  }

  @Test
  void whileLockedTheAttemptIsNotRunAndTheMessageSaysHowLongToWait() {
    for (int i = 0; i < 5; i++) {
      limiter.recordCodeRefusal(INSTALL);
    }
    AtomicInteger calls = new AtomicInteger();
    BaseException e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL,
        () -> calls.incrementAndGet()));
    assertEquals(ErrorCode.HR_PAIRING_CODE_LOCKED, e.getErrorCode());
    assertEquals("Too many wrong codes. Wait 15 minutes and try again.", e.getMessage());
    assertEquals(0, calls.get(), "the service is never reached while locked");
    verify(metrics).increment(eq(HrMetricsConstants.APP_PAIRINGS_TOTAL), anyDouble(),
        eq("module"), eq("hr"),
        eq(HrMetricsConstants.TAG_SHOP), eq("unknown"),
        eq(HrMetricsConstants.TAG_EVENT), eq("failed"),
        eq(HrMetricsConstants.TAG_REASON), eq("locked"));

    clock.advance(Duration.ofMinutes(10).plusSeconds(1)); // 4 min 59 s left
    e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL,
        () -> calls.incrementAndGet()));
    assertEquals("Too many wrong codes. Wait 5 minutes and try again.", e.getMessage(),
        "minutes are rounded up");

    clock.advance(Duration.ofMinutes(4).plusSeconds(58)); // 1 s left
    e = assertThrows(BaseException.class, () -> limiter.guardCodeAttempt(INSTALL,
        () -> calls.incrementAndGet()));
    assertEquals("Too many wrong codes. Wait 1 minute and try again.", e.getMessage(),
        "never 0 minutes, and the singular for one");

    clock.advance(Duration.ofSeconds(1));
    assertEquals(1, limiter.guardCodeAttempt(INSTALL, () -> calls.incrementAndGet()));
  }

  @Test
  void theLockedMessageUsesTheSingularForOneMinuteOnly() {
    assertEquals("Too many wrong codes. Wait 1 minute and try again.",
        AppRateLimiter.lockedMessage(1));
    assertEquals("Too many wrong codes. Wait 2 minutes and try again.",
        AppRateLimiter.lockedMessage(2));
    assertEquals("Too many wrong codes. Wait 15 minutes and try again.",
        AppRateLimiter.lockedMessage(15));
    // the template the app and the design table publish is untouched
    assertEquals("Too many wrong codes. Wait %d minutes and try again.",
        ErrorCode.HR_PAIRING_CODE_LOCKED.getMessage());
    // 61 s rounds up to 2 minutes; 60 s is exactly one
    assertEquals("Too many wrong codes. Wait 2 minutes and try again.",
        limiter.locked(Duration.ofSeconds(61)).getMessage());
    assertEquals("Too many wrong codes. Wait 1 minute and try again.",
        limiter.locked(Duration.ofSeconds(60)).getMessage());
  }

  @Test
  void aLockedKeyAdmitsNothingThroughTheGenericWindowEither() {
    for (int i = 0; i < 5; i++) {
      limiter.recordCodeRefusal(INSTALL);
    }
    assertFalse(limiter.tryAcquire(AppRateLimiter.CODE_FAIL_PREFIX + INSTALL, 100,
        Duration.ofMinutes(15)));
  }

  @Test
  void aBlankInstallationIdIsNotLimited() {
    assertEquals("ok", limiter.guardCodeAttempt(null, () -> "ok"));
    assertEquals("ok", limiter.guardCodeAttempt(" ", () -> "ok"));
    assertEquals(0, limiter.size());
    verify(metrics, never()).increment(anyString(), anyDouble(), anyString(), anyString(),
        anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
  }

  // ---- sweep -----------------------------------------------------------------------------------

  @Test
  void sweepDropsKeysIdleForTheirWindowAndKeepsLocks() {
    limiter.tryAcquirePunch("p-idle"); // 60 s window
    limiter.tryAcquire("short", 5, Duration.ofSeconds(5));
    for (int i = 0; i < 5; i++) {
      limiter.recordCodeRefusal(INSTALL); // locked 15 min
    }
    limiter.recordCodeRefusal("other-install"); // one refusal, 15 min window
    assertEquals(4, limiter.size());

    clock.advance(Duration.ofSeconds(5));
    limiter.sweep();
    assertEquals(3, limiter.size(), "the 5 s key is idle");

    clock.advance(Duration.ofSeconds(55)); // 60 s after the punch
    limiter.sweep();
    assertEquals(2, limiter.size(), "the punch key is idle");

    clock.advance(Duration.ofMinutes(13)); // 14 min after the refusals
    limiter.sweep();
    assertEquals(2, limiter.size(), "the refusal is inside its 15 min window; the lock holds");
    assertEquals(Duration.ofMinutes(1), limiter.codeLockRemaining(INSTALL));

    clock.advance(Duration.ofMinutes(1)); // 15 min: the refusal aged out and the lock ended
    limiter.sweep();
    assertEquals(0, limiter.size());
  }

  @Test
  void sweepKeepsAKeyThatIsStillInsideItsWindow() {
    limiter.tryAcquirePunch("p1");
    clock.advance(Duration.ofSeconds(59));
    limiter.sweep();
    assertEquals(1, limiter.size());
    // and the count it holds still applies
    for (int i = 0; i < 19; i++) {
      assertTrue(limiter.tryAcquirePunch("p1"));
    }
    assertFalse(limiter.tryAcquirePunch("p1"));
  }

  /** A clock the test moves by hand. */
  static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
