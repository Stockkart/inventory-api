package com.inventory.hr.service;

import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.repository.AppHealthAlertRepository;
import com.inventory.hr.rest.dto.response.AppHealthResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * The signature-failure rate per shop and its alert (R10.4–R10.6, design DD10).
 *
 * <p>Every app punch attempt the server answers is one call to {@link #record}: from {@code
 * AttendanceAppAuthInterceptor} when the envelope fails (always a failure) and from {@code
 * AppPunchService} once per punch call (a failure when the result is {@code signature_failed}).
 * The call is wrapped by the callers and by this class so a health problem never changes a punch
 * answer: the alert is advisory (design "Error handling").
 *
 * <p>Per shop, {@value #WINDOW_MINUTES} one-minute buckets of (attempts, failures) are kept in a
 * ring indexed by the minute number; a bucket written in an earlier hour is reset before it is
 * reused, so a sum over the ring is always the last 60 minutes. {@link #recalculate} runs every 5
 * minutes (R10.4):
 *
 * <ul>
 *   <li>rate = failures / attempts over the window (0 when there were no attempts);
 *   <li>a shop with no open alert, {@code attempts >= } {@value #MIN_ATTEMPTS} and {@code rate >
 *       } {@value #MAX_RATE} gets one {@link AppHealthAlert} row in {@code hr_app_alerts} with the
 *       numbers that raised it (R10.5); fewer than 20 attempts never raise one;
 *   <li>an open alert is cleared ({@code clearedAt = now}) once a <em>full</em> window — 60 minutes
 *       after the alert was raised and 60 minutes after this instance started observing — has a
 *       rate at or below 1 % (R10.6). A shop that went quiet (no attempts) has rate 0 and clears.
 * </ul>
 *
 * <p><b>Single-instance state</b> (handbook {@code IN_MEMORY_STATE.md}). The buckets live in
 * memory: a restart starts the window empty, so the first 60 minutes after a deploy cannot raise
 * (too few attempts) or clear (the window is not full) an alert. With N instances each sees only
 * its share of a shop's punches, so a shop needs N × 20 attempts an hour before any instance can
 * alert; the alert rows themselves are in Mongo and an open one is never duplicated (one open row
 * per shop is checked before an insert). Shops with no bucket in the last 60 minutes are dropped
 * at each recalculation, so memory is bounded by the shops active in the last hour.
 */
@Slf4j
@Service
public class AppPunchHealthService {

  /** The rolling window of R10.4. */
  public static final int WINDOW_MINUTES = 60;

  /** Fewer attempts than this in the window never raise an alert (R10.5). */
  public static final int MIN_ATTEMPTS = 20;

  /** The alert threshold: strictly above this raises, at or below this clears (R10.5, R10.6). */
  public static final double MAX_RATE = 0.01;

  /** How often the rate is recalculated (R10.4 says at least every 5 minutes). */
  static final long RECALCULATE_EVERY_MS = 5 * 60_000L;

  static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);

  @Autowired private AppHealthAlertRepository alerts;

  @Autowired private HrPermissionGuard guard;

  private final Map<String, ShopWindow> windows = new ConcurrentHashMap<>();

  private Clock clock = Clock.systemUTC();

  /** When this instance began observing; a window is full only 60 minutes after this. */
  private Instant startedAt = Instant.now(clock);

  public AppPunchHealthService() {}

  /** For tests: a pinned clock, an alert repository double and a guard double. */
  public AppPunchHealthService(
      AppHealthAlertRepository alerts, HrPermissionGuard guard, Clock clock) {
    this.alerts = alerts;
    this.guard = guard;
    setClock(clock);
  }

  /** Tests pin the clock; also resets the start of observation to the clock's now. */
  void setClock(Clock clock) {
    this.clock = clock;
    this.startedAt = Instant.now(clock);
  }

  // ---- feeding -------------------------------------------------------------------------------

  /**
   * One app punch attempt for the shop, {@code failed} when its result was {@code
   * signature_failed}. Never throws: an unknown shop ({@code null} or blank, as when the envelope
   * named no pairing) is dropped, and any error is logged and swallowed so the punch answer is not
   * affected.
   */
  public void record(String shopId, boolean failed) {
    try {
      if (!StringUtils.hasText(shopId)) {
        return;
      }
      long minute = minute(now());
      windows.computeIfAbsent(shopId, k -> new ShopWindow()).add(minute, failed);
    } catch (RuntimeException e) {
      log.warn("[hr-app-health] record failed for shop {}: {}", shopId, e.toString());
    }
  }

  // ---- recalculation -------------------------------------------------------------------------

  /**
   * Recomputes every shop's rate and opens or clears alerts (R10.4–R10.6). Runs on every instance;
   * safe to run twice in a row (an open alert is never inserted twice, a cleared one is not
   * cleared again). A failure for one shop is logged and the other shops still run.
   */
  @Scheduled(fixedDelay = RECALCULATE_EVERY_MS)
  public void recalculate() {
    Instant now = now();
    long minute = minute(now);
    List<AppHealthAlert> open;
    try {
      open = alerts.findByClearedAtIsNull();
    } catch (RuntimeException e) {
      log.warn("[hr-app-health] could not read open alerts: {}", e.toString());
      open = List.of();
    }
    Set<String> shops = new LinkedHashSet<>(windows.keySet());
    for (AppHealthAlert a : open) {
      if (a.getShopId() != null) {
        shops.add(a.getShopId());
      }
    }
    for (String shopId : shops) {
      try {
        evaluate(shopId, now, minute, open.stream()
            .filter(a -> shopId.equals(a.getShopId())).findFirst());
      } catch (RuntimeException e) {
        log.warn("[hr-app-health] recalculation failed for shop {}: {}", shopId, e.toString());
      }
    }
    windows.entrySet().removeIf(e -> e.getValue().isIdle(minute));
  }

  private void evaluate(String shopId, Instant now, long minute, Optional<AppHealthAlert> open) {
    ShopWindow w = windows.get(shopId);
    Totals t = w != null ? w.totals(minute) : Totals.NONE;
    double rate = t.rate();
    if (open.isEmpty()) {
      if (t.attempts >= MIN_ATTEMPTS && rate > MAX_RATE) {
        alerts.save(new AppHealthAlert(null, shopId, rate, t.failures, t.attempts, now, null));
        log.warn("[hr-app-health] alert raised for shop {}: {} of {} app punches failed "
            + "verification in the last {} min", shopId, t.failures, t.attempts, WINDOW_MINUTES);
      }
      return;
    }
    AppHealthAlert alert = open.get();
    if (rate <= MAX_RATE && windowIsFull(alert, now)) {
      alert.setClearedAt(now);
      alerts.save(alert);
      log.info("[hr-app-health] alert cleared for shop {}: {} of {} failed in the last {} min",
          shopId, t.failures, t.attempts, WINDOW_MINUTES);
    }
  }

  /** A full window has passed since the alert was raised and since this instance began observing. */
  private boolean windowIsFull(AppHealthAlert alert, Instant now) {
    Instant raisedAt = alert.getRaisedAt() != null ? alert.getRaisedAt() : Instant.MIN;
    Instant since = raisedAt.isAfter(startedAt) ? raisedAt : startedAt;
    return !since.plus(WINDOW).isAfter(now);
  }

  // ---- reads ---------------------------------------------------------------------------------

  /**
   * {@code GET /api/v1/hr/attendance-app/health}: the shop's open alert, if any, for the HR
   * dashboard panel (R10.5). Needs {@code VIEW_ATTENDANCE}.
   */
  public AppHealthResponse openAlerts(String userId, String shopId) {
    guard.require(userId, shopId, HrPermission.VIEW_ATTENDANCE);
    List<AppHealthResponse.Alert> rows = alerts.findByShopIdAndClearedAtIsNull(shopId)
        .map(a -> List.of(new AppHealthResponse.Alert(a.getShopId(), a.getRate(), a.getFailed(),
            a.getAttempts(), a.getRaisedAt())))
        .orElse(List.of());
    return new AppHealthResponse(rows);
  }

  /** The shop's current window totals (attempts, failures); for tests and the log line. */
  Totals totals(String shopId) {
    ShopWindow w = windows.get(shopId);
    return w != null ? w.totals(minute(now())) : Totals.NONE;
  }

  /** Shops with buckets in memory; for tests. */
  int size() {
    return windows.size();
  }

  private Instant now() {
    return Instant.now(clock);
  }

  static long minute(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), 60L);
  }

  /** Sum over one shop's window. */
  record Totals(int attempts, int failures) {
    static final Totals NONE = new Totals(0, 0);

    double rate() {
      return attempts == 0 ? 0d : (double) failures / attempts;
    }
  }

  /** One shop's ring of 60 one-minute buckets. All methods are synchronised. */
  static final class ShopWindow {
    private final long[] minutes = new long[WINDOW_MINUTES];
    private final int[] attempts = new int[WINDOW_MINUTES];
    private final int[] failures = new int[WINDOW_MINUTES];
    private long lastMinute = Long.MIN_VALUE;

    ShopWindow() {
      Arrays.fill(minutes, Long.MIN_VALUE);
    }

    synchronized void add(long minute, boolean failed) {
      int i = (int) Math.floorMod(minute, (long) WINDOW_MINUTES);
      if (minutes[i] != minute) {
        minutes[i] = minute;
        attempts[i] = 0;
        failures[i] = 0;
      }
      attempts[i]++;
      if (failed) {
        failures[i]++;
      }
      if (minute > lastMinute) {
        lastMinute = minute;
      }
    }

    /** Buckets whose minute lies in {@code (now − 60, now]}. */
    synchronized Totals totals(long nowMinute) {
      int a = 0;
      int f = 0;
      for (int i = 0; i < WINDOW_MINUTES; i++) {
        long age = nowMinute - minutes[i];
        if (minutes[i] != Long.MIN_VALUE && age >= 0 && age < WINDOW_MINUTES) {
          a += attempts[i];
          f += failures[i];
        }
      }
      return new Totals(a, f);
    }

    /** No bucket inside the window any more. */
    synchronized boolean isIdle(long nowMinute) {
      return nowMinute - lastMinute >= WINDOW_MINUTES;
    }
  }
}
