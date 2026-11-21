package com.inventory.plan.service.voucher;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps unknown voucher codes per caller per minute (validate, quote and checkout), so guessing codes
 * is slow. Only misses count: re-pricing a cart with a real code never trips it. Per instance: with N
 * instances a caller gets at most N times the limit.
 */
@Component
public class VoucherLookupRateLimiter {

  private static final long WINDOW_MILLIS = 60_000;
  private static final int MAX_TRACKED_CALLERS = 10_000;

  @Value("${voucher.lookup.max-misses-per-minute:10}")
  int maxMissesPerMinute = 10;

  Clock clock = Clock.systemUTC();

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  /** Throws 429 when any of the caller's keys has used up this minute's misses. Null keys are skipped. */
  public void checkAllowed(String... callerKeys) {
    long windowStart = windowStart();
    for (String key : callerKeys) {
      if (key == null) {
        continue;
      }
      Window window = windows.get(key);
      if (window != null && window.start == windowStart && window.misses.get() >= maxMissesPerMinute) {
        throw new BaseException(ErrorCode.TOO_MANY_REQUESTS,
            "Too many unknown voucher codes. Try again in a minute.");
      }
    }
  }

  /** Counts one unknown code against each of the caller's keys. */
  public void recordMiss(String... callerKeys) {
    long windowStart = windowStart();
    for (String key : callerKeys) {
      if (key == null) {
        continue;
      }
      windows.compute(key,
          (k, current) -> current == null || current.start != windowStart ? new Window(windowStart) : current)
          .misses.incrementAndGet();
    }
    if (windows.size() > MAX_TRACKED_CALLERS) {
      windows.entrySet().removeIf(entry -> entry.getValue().start != windowStart);
    }
  }

  public static String shopKey(String shopId) {
    return shopId == null || shopId.isBlank() ? null : "shop:" + shopId;
  }

  public static String userKey(String userId) {
    return userId == null || userId.isBlank() ? null : "user:" + userId;
  }

  private long windowStart() {
    long now = clock.millis();
    return now - (now % WINDOW_MILLIS);
  }

  private static final class Window {
    private final long start;
    private final AtomicInteger misses = new AtomicInteger();

    private Window(long start) {
      this.start = start;
    }
  }
}
