package com.inventory.plan.service.referral;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps referral code checks per caller per minute, so the endpoint is not a free way to enumerate
 * valid codes (r4.16). Per instance: with N instances a caller gets at most N times the limit.
 */
@Component
public class ReferralCheckRateLimiter {

  private static final long WINDOW_MILLIS = 60_000;

  @Value("${referral.validate.max-per-minute:10}")
  int maxPerMinute;

  Clock clock = Clock.systemUTC();

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public void acquire(String callerKey) {
    long now = clock.millis();
    long windowStart = now - (now % WINDOW_MILLIS);
    Window window = windows.compute(callerKey,
        (key, current) -> current == null || current.start != windowStart ? new Window(windowStart) : current);
    if (window.count.incrementAndGet() > maxPerMinute) {
      throw new BaseException(ErrorCode.TOO_MANY_REQUESTS, "Too many referral code checks. Try again in a minute.");
    }
    if (windows.size() > 10_000) {
      windows.entrySet().removeIf(entry -> entry.getValue().start != windowStart);
    }
  }

  private static final class Window {
    private final long start;
    private final AtomicInteger count = new AtomicInteger();

    private Window(long start) {
      this.start = start;
    }
  }
}
