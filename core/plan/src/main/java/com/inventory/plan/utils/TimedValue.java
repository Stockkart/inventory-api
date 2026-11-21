package com.inventory.plan.utils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * One value kept for a short time on this instance, for reads that every page load repeats (the
 * plan catalogue, the add-on catalogue, the running campaign). The owning service calls
 * {@link #evict} after its own writes; other instances see a change after at most the TTL.
 */
public final class TimedValue<T> {

  private final Duration ttl;
  private final Clock clock;
  private volatile Entry<T> entry;

  public TimedValue(Duration ttl) {
    this(ttl, Clock.systemUTC());
  }

  public TimedValue(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  public T get(Supplier<T> load) {
    Instant now = clock.instant();
    Entry<T> current = entry;
    if (current != null && now.isBefore(current.expiresAt())) {
      return current.value();
    }
    T value = load.get();
    entry = new Entry<>(value, now.plus(ttl));
    return value;
  }

  public void evict() {
    entry = null;
  }

  private record Entry<T>(T value, Instant expiresAt) {}
}
