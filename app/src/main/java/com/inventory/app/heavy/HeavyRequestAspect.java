package com.inventory.app.heavy;

import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import com.inventory.common.heavy.HeavyRequestLimiter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link HeavyRequest} controller method inside a slot of its kind (see
 * {@link HeavyRequestLimiter}); a request that gets no slot in time is answered 503 "busy" without
 * running. Also publishes, per kind: {@code inventory_heavy_in_use}, {@code inventory_heavy_waiting},
 * {@code inventory_heavy_rejected_total}.
 */
@Aspect
@Component
public class HeavyRequestAspect {

  private final HeavyRequestLimiter limiter;

  public HeavyRequestAspect(HeavyRequestLimiter limiter, MeterRegistry registry) {
    this.limiter = limiter;
    for (HeavyKind kind : HeavyKind.values()) {
      Gauge.builder("inventory_heavy_in_use", limiter, l -> l.inUse(kind))
          .description("Heavy requests running now, per kind").tag("kind", kind.key).register(registry);
      Gauge.builder("inventory_heavy_waiting", limiter, l -> l.waiting(kind))
          .description("Heavy web requests waiting for a slot, per kind").tag("kind", kind.key).register(registry);
      FunctionCounter.builder("inventory_heavy_rejected", limiter, l -> l.rejected(kind))
          .description("Heavy requests answered busy (no slot in time), per kind").tag("kind", kind.key)
          .register(registry);
    }
  }

  @Around("@annotation(heavy)")
  public Object inSlot(ProceedingJoinPoint joinPoint, HeavyRequest heavy) throws Throwable {
    try (HeavyRequestLimiter.Permit ignored = limiter.acquire(heavy.value())) {
      return joinPoint.proceed();
    }
  }
}
