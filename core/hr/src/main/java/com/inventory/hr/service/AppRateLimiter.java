package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPairingEvent;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPairingFailure;
import com.inventory.metrics.MetricsWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-key sliding-window limits for the Attendance app (R2.5, R9.9, design DD10), kept in memory.
 *
 * <p>Two uses, two key prefixes:
 *
 * <ul>
 *   <li>{@code punch:<pairingId>} — at most {@value #PUNCH_LIMIT} punch requests per pairing in any
 *       rolling 60 seconds ({@link #tryAcquirePunch}); the 21st is refused without recording a
 *       punch (R9.9).
 *   <li>{@code code-fail:<installationId>} — the pairing-code lockout: after {@value
 *       #CODE_FAILURE_LIMIT} refused code attempts from one installation within 15 minutes, every
 *       attempt from it is refused for the next 15 minutes with the minutes left to wait (R2.5,
 *       {@link #guardCodeAttempt}). A code that matches no document counts here only, because there
 *       is no code document to count it on.
 * </ul>
 *
 * <p>Semantics of a window: a request at time {@code now} is admitted when fewer than {@code
 * limit} requests were admitted in {@code (now − window, now]}; a refused request is not recorded,
 * so a client hammering the limit is not pushed further back. The lockout is the one place a
 * refusal is recorded (it is the thing being counted).
 *
 * <p><b>Single-instance state</b> (handbook {@code IN_MEMORY_STATE.md}). Production runs one
 * backend instance; with N instances each has its own counters, so a pairing could send up to
 * N × 20 punches a minute and an installation could try N × 5 codes before the lock. Nothing is
 * needed for correctness: the per-code attempt count that invalidates a code (R9.10) lives on the
 * code document, not here. A Mongo round trip per punch for a counter would cost more than the
 * limit protects. Entries idle longer than their window are dropped once a minute by {@link
 * #sweep}, so memory is bounded by the keys active in the last window.
 */
@Slf4j
@Component
public class AppRateLimiter {

  /** Punches per pairing per rolling minute (R9.9). */
  public static final int PUNCH_LIMIT = 20;

  public static final Duration PUNCH_WINDOW = Duration.ofSeconds(60);

  /** Refused code attempts per installation before the lock (R2.5). */
  public static final int CODE_FAILURE_LIMIT = 5;

  public static final Duration CODE_FAILURE_WINDOW = Duration.ofMinutes(15);

  /** How long an installation is locked after the 5th refusal (R2.5). */
  public static final Duration CODE_LOCK = Duration.ofMinutes(15);

  static final String PUNCH_PREFIX = "punch:";
  static final String CODE_FAIL_PREFIX = "code-fail:";

  /**
   * The refusals of R2.14 that count as "a refused code attempt". Malformed input (400), a missing
   * employee record or a server error are not the worker guessing codes and do not count.
   */
  static final Set<ErrorCode> CODE_REFUSALS = EnumSet.of(
      ErrorCode.HR_PAIRING_CODE_EXPIRED,
      ErrorCode.HR_PAIRING_CODE_USED,
      ErrorCode.HR_PAIRING_CODE_CANCELLED,
      ErrorCode.HR_PAIRING_CODE_WRONG,
      ErrorCode.HR_APP_OFF,
      ErrorCode.HR_PAIRING_EMPLOYEE_EXITED,
      ErrorCode.HR_TOO_MANY_ATTEMPTS);

  @Autowired private MetricsWrapper metrics;

  private final Map<String, Window> windows = new ConcurrentHashMap<>();

  private Clock clock = Clock.systemUTC();

  public AppRateLimiter() {}

  /** For tests: a pinned clock and a metrics double. */
  public AppRateLimiter(MetricsWrapper metrics, Clock clock) {
    this.metrics = metrics;
    this.clock = clock;
  }

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  // ---- generic sliding window ----------------------------------------------------------------

  /**
   * Admits the request when fewer than {@code limit} requests were admitted for {@code key} in
   * the last {@code window}, and records it. A refused request is not recorded. A key under a
   * lock (see {@link #guardCodeAttempt}) admits nothing until the lock ends.
   */
  public boolean tryAcquire(String key, int limit, Duration window) {
    Instant now = now();
    return window(key, window).tryAcquire(now, limit, window);
  }

  /** The punch limit of R9.9 for one pairing. */
  public boolean tryAcquirePunch(String pairingId) {
    return tryAcquire(PUNCH_PREFIX + pairingId, PUNCH_LIMIT, PUNCH_WINDOW);
  }

  // ---- pairing-code lockout --------------------------------------------------------------------

  /**
   * Runs one claim or complete attempt from an installation under the lockout rule (R2.5): refused
   * at once with {@link ErrorCode#HR_PAIRING_CODE_LOCKED} and the minutes left while the
   * installation is locked; otherwise the attempt runs, and a refusal from {@link #CODE_REFUSALS}
   * is counted against the installation — the 5th within 15 minutes locks it for 15 minutes.
   *
   * @param installationId the phone's installation id; a blank one is not limited (the service
   *     rejects it as invalid input)
   */
  public <T> T guardCodeAttempt(String installationId, Supplier<T> attempt) {
    if (installationId == null || installationId.isBlank()) {
      return attempt.get();
    }
    Duration wait = codeLockRemaining(installationId);
    if (!wait.isZero()) {
      throw locked(wait);
    }
    try {
      return attempt.get();
    } catch (BaseException e) {
      if (CODE_REFUSALS.contains(e.getErrorCode())) {
        recordCodeRefusal(installationId);
      }
      throw e;
    }
  }

  /** Time left on the installation's lock; {@link Duration#ZERO} when it is not locked. */
  public Duration codeLockRemaining(String installationId) {
    Window w = windows.get(CODE_FAIL_PREFIX + installationId);
    return w != null ? w.lockRemaining(now()) : Duration.ZERO;
  }

  /**
   * One refused code attempt from the installation. The 5th within 15 minutes starts a 15-minute
   * lock (R2.5).
   *
   * @return the lock just started, or {@link Duration#ZERO} when the installation is not locked
   */
  public Duration recordCodeRefusal(String installationId) {
    Instant now = now();
    return window(CODE_FAIL_PREFIX + installationId, CODE_FAILURE_WINDOW)
        .recordAndLockAt(now, CODE_FAILURE_LIMIT, CODE_FAILURE_WINDOW, CODE_LOCK);
  }

  /** The R2.5 refusal, with whole minutes rounded up (never "0 minutes"). */
  BaseException locked(Duration wait) {
    // round the full remaining time up, not its whole seconds: 13 min 0.5 s left is "14 minutes"
    long minutes = Math.max(1, Math.ceilDiv(wait.toNanos(), Duration.ofMinutes(1).toNanos()));
    if (metrics != null) {
      metrics.increment(HrMetricsConstants.APP_PAIRINGS_TOTAL, 1,
          "module", HrMetricsConstants.MODULE,
          HrMetricsConstants.TAG_SHOP, "unknown",
          HrMetricsConstants.TAG_EVENT, AppPairingEvent.FAILED,
          HrMetricsConstants.TAG_REASON, AppPairingFailure.LOCKED);
    }
    return new BaseException(ErrorCode.HR_PAIRING_CODE_LOCKED, lockedMessage(minutes));
  }

  /**
   * The {@code HR_PAIRING_CODE_LOCKED} template ("Wait %d minutes") filled in, with the unit in
   * the singular for one minute: "Wait 1 minute and try again." The template itself is kept as
   * the design table and {@code attendance-protocol/src/messages.ts} publish it.
   */
  static String lockedMessage(long minutes) {
    String text = String.format(Locale.ROOT, ErrorCode.HR_PAIRING_CODE_LOCKED.getMessage(),
        minutes);
    return minutes == 1 ? text.replace("1 minutes", "1 minute") : text;
  }

  // ---- housekeeping ----------------------------------------------------------------------------

  /**
   * Drops keys that admitted nothing within their window and hold no lock. Runs once a minute on
   * every instance; harmless with more than one (each sweeps its own map).
   */
  @Scheduled(fixedDelay = 60_000)
  public void sweep() {
    Instant now = now();
    int before = windows.size();
    windows.entrySet().removeIf(e -> e.getValue().isIdle(now));
    int removed = before - windows.size();
    if (removed > 0) {
      log.debug("[hr-app-limits] swept {} idle keys, {} remain", removed, windows.size());
    }
  }

  /** Keys currently held; for tests and the log line. */
  int size() {
    return windows.size();
  }

  private Window window(String key, Duration window) {
    return windows.compute(key, (k, w) -> {
      if (w == null) {
        return new Window(window);
      }
      w.widen(window);
      return w;
    });
  }

  private Instant now() {
    return Instant.now(clock);
  }

  /** One key's admitted timestamps (oldest first) and lock. All methods are synchronised. */
  static final class Window {
    private final ArrayDeque<Instant> admitted = new ArrayDeque<>();
    private Instant lockedUntil = Instant.MIN;
    private Instant lastTouched = Instant.MIN;
    /** The longest window or lock this key was ever used with; decides when it is idle. */
    private Duration idleAfter;

    Window(Duration idleAfter) {
      this.idleAfter = idleAfter;
    }

    synchronized void widen(Duration d) {
      if (d.compareTo(idleAfter) > 0) {
        idleAfter = d;
      }
    }

    synchronized boolean tryAcquire(Instant now, int limit, Duration window) {
      lastTouched = now;
      if (now.isBefore(lockedUntil)) {
        return false;
      }
      prune(now, window);
      if (admitted.size() >= limit) {
        return false;
      }
      admitted.addLast(now);
      return true;
    }

    synchronized Duration recordAndLockAt(Instant now, int limit, Duration window, Duration lock) {
      lastTouched = now;
      widen(lock);
      if (now.isBefore(lockedUntil)) {
        return Duration.between(now, lockedUntil);
      }
      prune(now, window);
      admitted.addLast(now);
      if (admitted.size() >= limit) {
        lockedUntil = now.plus(lock);
        admitted.clear();
        return lock;
      }
      return Duration.ZERO;
    }

    synchronized Duration lockRemaining(Instant now) {
      return now.isBefore(lockedUntil) ? Duration.between(now, lockedUntil) : Duration.ZERO;
    }

    synchronized boolean isIdle(Instant now) {
      if (now.isBefore(lockedUntil)) {
        return false;
      }
      return !lastTouched.plus(idleAfter).isAfter(now);
    }

    /** Keeps the entries in {@code (now − window, now]}. */
    private void prune(Instant now, Duration window) {
      Instant cutoff = now.minus(window);
      while (!admitted.isEmpty() && !admitted.peekFirst().isAfter(cutoff)) {
        admitted.pollFirst();
      }
    }
  }
}
