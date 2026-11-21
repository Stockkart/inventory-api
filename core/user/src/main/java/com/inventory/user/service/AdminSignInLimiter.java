package com.inventory.user.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits failed admin sign-ins per container, before any password is checked: per client address and
 * account, per client address, and across all clients. Failures from one address never lock out an
 * account for other addresses; the stored account lock ({@link AdminAuthService}) is only a high
 * backstop. Fixed windows, like {@code ReferralCheckRateLimiter}; with N containers a client gets at
 * most N times each limit.
 */
@Component
public class AdminSignInLimiter {

  static final String TOO_MANY = "Too many sign-in attempts. Try again later.";

  @Value("${admin.sign-in.window-seconds:900}")
  long windowSeconds = 900;

  @Value("${admin.sign-in.max-failures-per-address-and-account:5}")
  int maxPerAddressAndAccount = 5;

  @Value("${admin.sign-in.max-failures-per-address:20}")
  int maxPerAddress = 20;

  @Value("${admin.sign-in.max-failures-per-minute:300}")
  int maxPerMinuteGlobal = 300;

  Clock clock = Clock.systemUTC();

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  /** Throws 429 when this address (or everyone) has failed too often in the current window. */
  public void checkAllowed(String clientAddress, String email) {
    long now = clock.millis();
    if (count(pairKey(clientAddress, email), now, windowMillis()) >= maxPerAddressAndAccount
        || count(addressKey(clientAddress), now, windowMillis()) >= maxPerAddress
        || count("all", now, 60_000) >= maxPerMinuteGlobal) {
      throw new BaseException(ErrorCode.TOO_MANY_REQUESTS, TOO_MANY);
    }
  }

  public void recordFailure(String clientAddress, String email) {
    long now = clock.millis();
    increment(pairKey(clientAddress, email), now, windowMillis());
    increment(addressKey(clientAddress), now, windowMillis());
    increment("all", now, 60_000);
    if (windows.size() > 10_000) {
      windows.entrySet().removeIf(e -> e.getValue().start + e.getValue().length <= now);
    }
  }

  private long windowMillis() {
    return windowSeconds * 1000;
  }

  private int count(String key, long now, long length) {
    Window w = windows.get(key + "@" + length);
    return w == null || w.start != start(now, length) ? 0 : w.count.get();
  }

  private void increment(String key, long now, long length) {
    long start = start(now, length);
    windows.compute(key + "@" + length,
        (k, current) -> current == null || current.start != start ? new Window(start, length) : current)
        .count.incrementAndGet();
  }

  private static long start(long now, long length) {
    return now - (now % length);
  }

  private static String addressKey(String clientAddress) {
    return "ip:" + (clientAddress == null ? "" : clientAddress);
  }

  private static String pairKey(String clientAddress, String email) {
    return addressKey(clientAddress) + "|" + (email == null ? "" : email);
  }

  private static final class Window {
    private final long start;
    private final long length;
    private final AtomicInteger count = new AtomicInteger();

    private Window(long start, long length) {
      this.start = start;
      this.length = length;
    }
  }
}
