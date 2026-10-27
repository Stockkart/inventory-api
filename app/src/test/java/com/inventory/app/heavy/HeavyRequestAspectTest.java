package com.inventory.app.heavy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import com.inventory.common.heavy.HeavyRequestBusyException;
import com.inventory.common.heavy.HeavyRequestLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.env.MockEnvironment;

/** An annotated method runs inside a slot of its kind; with no slot it does not run at all. */
class HeavyRequestAspectTest {

  static class Reports {
    final AtomicInteger runs = new AtomicInteger();
    HeavyRequestLimiter limiter;
    int inUseDuringRun = -1;

    @HeavyRequest(HeavyKind.EXPORT)
    public byte[] pdf() {
      runs.incrementAndGet();
      inUseDuringRun = limiter.inUse(HeavyKind.EXPORT);
      return new byte[] {1};
    }

    @HeavyRequest(HeavyKind.EXPORT)
    public byte[] broken() {
      throw new IllegalStateException("render failed");
    }

    public String light() {
      return "ok";
    }
  }

  private static Reports proxied(Reports target, HeavyRequestLimiter limiter, SimpleMeterRegistry registry) {
    target.limiter = limiter;
    AspectJProxyFactory factory = new AspectJProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAspect(new HeavyRequestAspect(limiter, registry));
    return factory.getProxy();
  }

  @Test
  void runsInASlotAndGivesItBack() {
    HeavyRequestLimiter limiter = new HeavyRequestLimiter(new MockEnvironment());
    Reports target = new Reports();
    Reports reports = proxied(target, limiter, new SimpleMeterRegistry());

    assertThat(reports.pdf()).containsExactly(1);
    assertThat(target.inUseDuringRun).isEqualTo(1);
    assertThat(limiter.inUse(HeavyKind.EXPORT)).isZero();

    assertThatThrownBy(reports::broken).isInstanceOf(IllegalStateException.class);
    assertThat(limiter.inUse(HeavyKind.EXPORT)).isZero(); // given back on errors too
  }

  @Test
  void withNoSlotTheMethodDoesNotRun() {
    MockEnvironment env = new MockEnvironment().withProperty("heavy.export.wait-ms", "50");
    HeavyRequestLimiter limiter = new HeavyRequestLimiter(env);
    Reports target = new Reports();
    Reports reports = proxied(target, limiter, new SimpleMeterRegistry());

    try (HeavyRequestLimiter.Permit held = limiter.acquire(HeavyKind.EXPORT)) {
      assertThatThrownBy(reports::pdf).isInstanceOf(HeavyRequestBusyException.class);
    }
    assertThat(target.runs).hasValue(0);
    assertThat(reports.light()).isEqualTo("ok"); // unannotated methods are untouched
  }

  @Test
  void publishesInUseWaitingAndRejectedPerKind() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    MockEnvironment env = new MockEnvironment().withProperty("heavy.export.wait-ms", "20");
    HeavyRequestLimiter limiter = new HeavyRequestLimiter(env);
    Reports reports = proxied(new Reports(), limiter, registry);

    try (HeavyRequestLimiter.Permit held = limiter.acquire(HeavyKind.EXPORT)) {
      assertThat(registry.get("inventory_heavy_in_use").tag("kind", "export").gauge().value()).isEqualTo(1.0);
      assertThatThrownBy(reports::pdf).isInstanceOf(HeavyRequestBusyException.class);
    }
    assertThat(registry.get("inventory_heavy_rejected").tag("kind", "export").functionCounter().count()).isEqualTo(1.0);
    assertThat(registry.get("inventory_heavy_waiting").tag("kind", "ocr").gauge().value()).isZero();
  }
}
