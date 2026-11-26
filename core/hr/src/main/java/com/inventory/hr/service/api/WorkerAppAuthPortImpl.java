package com.inventory.hr.service.api;

import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.HrWorkerCounters;
import com.inventory.user.api.port.WorkerAppAuthPort;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * {@link WorkerAppAuthPort} over HR's {@link AppRateLimiter} and the worker counters (worker-app
 * R14.1, R14.2; design DD11). HR sits above user and implements user's port here
 * (module-boundaries B6). The limiter key is {@code signup-ip:<ip>}, a row in {@code
 * IN_MEMORY_STATE.md}: per backend instance, in memory, 20 in any rolling hour. The two counters
 * are incremented through {@link HrWorkerCounters}, the one entry point for the worker meters.
 */
@Component
public class WorkerAppAuthPortImpl implements WorkerAppAuthPort {

  static final String SIGNUP_IP_KEY = "signup-ip:";
  static final Duration SIGNUP_IP_WINDOW = Duration.ofHours(1);
  /** The bucket of a sign-up whose address the server could not see. */
  static final String UNKNOWN_IP = "unknown";

  private final AppRateLimiter rateLimiter;
  private final HrWorkerCounters counters;

  public WorkerAppAuthPortImpl(AppRateLimiter rateLimiter, HrWorkerCounters counters) {
    this.rateLimiter = rateLimiter;
    this.counters = counters;
  }

  @Override
  public boolean admitSignup(String clientIp) {
    String ip = StringUtils.hasText(clientIp) ? clientIp.trim() : UNKNOWN_IP;
    return rateLimiter.tryAcquire(SIGNUP_IP_KEY + ip, SIGNUP_IP_LIMIT, SIGNUP_IP_WINDOW);
  }

  @Override
  public void workerSignedUp() {
    counters.workerSignedUp();
  }

  @Override
  public void appLogin(boolean success) {
    counters.appLogin(success);
  }
}
