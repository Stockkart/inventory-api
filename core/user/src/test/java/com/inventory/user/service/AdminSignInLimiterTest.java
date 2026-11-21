package com.inventory.user.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.inventory.common.exception.BaseException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminSignInLimiterTest {

  private static final Instant NOW = Instant.parse("2026-11-21T06:00:00Z");
  private AdminSignInLimiter limiter;

  @BeforeEach
  void setUp() {
    limiter = new AdminSignInLimiter();
    limiter.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void failuresFromOneAddressDoNotBlockTheSameAccountElsewhere() {
    for (int i = 0; i < 5; i++) {
      limiter.recordFailure("198.51.100.1", "ops@stockkart.in");
    }

    assertThrows(BaseException.class, () -> limiter.checkAllowed("198.51.100.1", "ops@stockkart.in"));
    assertDoesNotThrow(() -> limiter.checkAllowed("203.0.113.9", "ops@stockkart.in"));
    assertDoesNotThrow(() -> limiter.checkAllowed("198.51.100.1", "dev@stockkart.in"));
  }

  @Test
  void oneAddressTryingManyAccountsIsLimited() {
    for (int i = 0; i < 20; i++) {
      limiter.recordFailure("198.51.100.1", "user" + i + "@x.io");
    }

    assertThrows(BaseException.class, () -> limiter.checkAllowed("198.51.100.1", "new@x.io"));
    assertDoesNotThrow(() -> limiter.checkAllowed("203.0.113.9", "new@x.io"));
  }

  @Test
  void theWindowEnds() {
    for (int i = 0; i < 5; i++) {
      limiter.recordFailure("198.51.100.1", "ops@stockkart.in");
    }
    limiter.clock = Clock.fixed(NOW.plusSeconds(900), ZoneOffset.UTC);

    assertDoesNotThrow(() -> limiter.checkAllowed("198.51.100.1", "ops@stockkart.in"));
  }

  @Test
  void manyFailuresEverywhereAreCappedPerMinute() {
    limiter.maxPerMinuteGlobal = 3;
    for (int i = 0; i < 3; i++) {
      limiter.recordFailure("198.51.100." + i, "user" + i + "@x.io");
    }

    assertThrows(BaseException.class, () -> limiter.checkAllowed("203.0.113.9", "ops@stockkart.in"));
  }
}
