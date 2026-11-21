package com.inventory.plan.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TimedValueTest {

  private static final class MovableClock extends Clock {
    Instant now = Instant.parse("2026-11-21T00:00:00Z");
    @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }

  @Test
  void loadsOncePerTtlAndAgainAfterEvict() {
    MovableClock clock = new MovableClock();
    TimedValue<Integer> value = new TimedValue<>(Duration.ofSeconds(60), clock);
    AtomicInteger loads = new AtomicInteger();

    value.get(loads::incrementAndGet);
    value.get(loads::incrementAndGet);
    assertThat(loads).hasValue(1);

    clock.now = clock.now.plusSeconds(61);
    value.get(loads::incrementAndGet);
    assertThat(loads).hasValue(2);

    value.evict();
    assertThat(value.get(loads::incrementAndGet)).isEqualTo(3);
  }
}
