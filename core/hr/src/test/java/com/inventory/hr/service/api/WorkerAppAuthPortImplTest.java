package com.inventory.hr.service.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.HrWorkerCounters;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.user.api.port.WorkerAppAuthPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Worker-app R14.1, R14.2: 20 app sign-ups per IP per rolling hour; the two worker counters. */
@ExtendWith(MockitoExtension.class)
class WorkerAppAuthPortImplTest {

  private static final Instant NOW = Instant.parse("2026-05-12T09:00:00Z");

  @Mock private MetricsWrapper metrics;

  private final MutableClock clock = new MutableClock(NOW);

  private WorkerAppAuthPortImpl port() {
    return new WorkerAppAuthPortImpl(new AppRateLimiter(metrics, clock),
        new HrWorkerCounters(metrics));
  }

  @Test
  void twentyPerIpPerHourAndTheTwentyFirstWaitsForTheWindow() {
    WorkerAppAuthPortImpl port = port();
    for (int i = 0; i < WorkerAppAuthPort.SIGNUP_IP_LIMIT; i++) {
      assertTrue(port.admitSignup("203.0.113.9"), "sign-up " + (i + 1));
    }
    assertFalse(port.admitSignup("203.0.113.9"));
    // another address has its own window
    assertTrue(port.admitSignup("203.0.113.10"));
    // the window slides: an hour after the admits, the address is free again
    clock.now = NOW.plus(Duration.ofHours(1)).plusSeconds(1);
    assertTrue(port.admitSignup("203.0.113.9"));
  }

  @Test
  void aRefusedSignupIsNotRecordedAgainstTheAddress() {
    WorkerAppAuthPortImpl port = port();
    for (int i = 0; i < WorkerAppAuthPort.SIGNUP_IP_LIMIT - 1; i++) {
      assertTrue(port.admitSignup("203.0.113.9"));
    }
    clock.now = NOW.plus(Duration.ofMinutes(30));
    assertTrue(port.admitSignup("203.0.113.9"));
    assertFalse(port.admitSignup("203.0.113.9"));
    assertFalse(port.admitSignup("203.0.113.9"));
    // the 19 early admits leave the window; the one at +30 min and nothing else remains
    clock.now = NOW.plus(Duration.ofHours(1)).plusSeconds(1);
    for (int i = 0; i < WorkerAppAuthPort.SIGNUP_IP_LIMIT - 1; i++) {
      assertTrue(port.admitSignup("203.0.113.9"), "sign-up " + (i + 1));
    }
    assertFalse(port.admitSignup("203.0.113.9"));
  }

  @Test
  void aBlankAddressSharesOneBucket() {
    WorkerAppAuthPortImpl port = port();
    for (int i = 0; i < WorkerAppAuthPort.SIGNUP_IP_LIMIT; i++) {
      assertTrue(port.admitSignup(i % 2 == 0 ? null : "  "));
    }
    assertFalse(port.admitSignup(null));
    assertFalse(port.admitSignup(""));
  }

  @Test
  void theCountersCarryTheModuleAndTheResult() {
    WorkerAppAuthPortImpl port = port();

    port.workerSignedUp();
    port.appLogin(true);
    port.appLogin(false);

    verify(metrics).increment(HrMetricsConstants.WORKER_SIGNUPS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE);
    verify(metrics).increment(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, HrMetricsConstants.TAG_RESULT, "success");
    verify(metrics).increment(HrMetricsConstants.WORKER_APP_LOGINS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, HrMetricsConstants.TAG_RESULT, "error");
  }

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    Instant now;

    MutableClock(Instant now) {
      this.now = now;
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
