package com.inventory.common.heavy;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * How many heavy requests of each {@link HeavyKind} run at once in this container.
 *
 * <p>Per kind: a fair semaphore of {@code limit} slots (first come, first served), a line of at most
 * {@code queue} waiting web requests, and a maximum wait. A web request that finds the line full, or
 * waits too long, gets {@link HeavyRequestBusyException} (503 "busy", {@code Retry-After}) and holds
 * nothing. Background work (no web request on the thread, e.g. the phone upload's parsing on the OCR
 * pool) is not counted in the line — its pool bounds it — and may wait {@code background-wait-ms}.
 *
 * <p>A waiting web request holds one Tomcat thread and no database connection. The worst case is
 * the sum of the lines (16 by default) of Tomcat's 200 threads, so billing keeps its threads.
 *
 * <p>Per instance: each container has its own slots (what protects that container's memory). Listed
 * in the engineering handbook's IN_MEMORY_STATE.md.
 *
 * <p>Settings: {@code heavy.<kind>.limit}, {@code .queue}, {@code .wait-ms}, {@code .background-wait-ms}.
 */
@Slf4j
@Component
public class HeavyRequestLimiter {

  /** A held slot; {@link #close()} gives it back (once). */
  public interface Permit extends AutoCloseable {
    @Override
    void close();
  }

  /** One kind's slots, line and counters. */
  static final class Lane {
    final HeavyKind kind;
    final int limit;
    final int queue;
    final long waitMs;
    final long backgroundWaitMs;
    final Semaphore slots;
    final AtomicInteger waiting = new AtomicInteger();
    final AtomicLong rejected = new AtomicLong();

    Lane(HeavyKind kind, int limit, int queue, long waitMs, long backgroundWaitMs) {
      this.kind = kind;
      this.limit = Math.max(1, limit);
      this.queue = Math.max(0, queue);
      this.waitMs = Math.max(0, waitMs);
      this.backgroundWaitMs = backgroundWaitMs > 0 ? backgroundWaitMs : this.waitMs;
      this.slots = new Semaphore(this.limit, true);
    }
  }

  private final Map<HeavyKind, Lane> lanes = new EnumMap<>(HeavyKind.class);

  public HeavyRequestLimiter(Environment env) {
    for (HeavyKind k : HeavyKind.values()) {
      String p = "heavy." + k.key + ".";
      Lane lane = new Lane(k,
          env.getProperty(p + "limit", Integer.class, k.defaultLimit),
          env.getProperty(p + "queue", Integer.class, k.defaultQueue),
          env.getProperty(p + "wait-ms", Long.class, k.defaultWaitMs),
          env.getProperty(p + "background-wait-ms", Long.class, k.defaultBackgroundWaitMs));
      lanes.put(k, lane);
      log.info("[heavy] {}: {} at once, {} waiting, wait {} ms (background {} ms)",
          k.key, lane.limit, lane.queue, lane.waitMs, lane.backgroundWaitMs);
    }
  }

  /** Runs {@code work} in a slot of {@code kind}; see {@link #acquire}. */
  public <T> T run(HeavyKind kind, Supplier<T> work) {
    try (Permit ignored = acquire(kind)) {
      return work.get();
    }
  }

  /**
   * Takes a slot of {@code kind}, waiting in line if all are in use.
   *
   * @throws HeavyRequestBusyException when the line is full or the wait runs out
   */
  public Permit acquire(HeavyKind kind) {
    Lane lane = lanes.get(kind);
    boolean web = RequestContextHolder.getRequestAttributes() != null;
    try {
      // the timed form keeps first come, first served (plain tryAcquire() jumps the line)
      if (lane.slots.tryAcquire(0, TimeUnit.MILLISECONDS)) {
        return permit(lane);
      }
      if (web && !joinLine(lane)) {
        throw busy(lane, "line full");
      }
      try {
        long wait = web ? lane.waitMs : lane.backgroundWaitMs;
        if (lane.slots.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
          return permit(lane);
        }
        throw busy(lane, "waited " + wait + " ms");
      } finally {
        if (web) {
          lane.waiting.decrementAndGet();
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy(lane, "interrupted");
    }
  }

  private static boolean joinLine(Lane lane) {
    while (true) {
      int now = lane.waiting.get();
      if (now >= lane.queue) {
        return false;
      }
      if (lane.waiting.compareAndSet(now, now + 1)) {
        return true;
      }
    }
  }

  private static HeavyRequestBusyException busy(Lane lane, String why) {
    lane.rejected.incrementAndGet();
    log.warn("[heavy] {} busy ({}): {} in use, {} waiting", lane.kind.key, why,
        lane.limit - lane.slots.availablePermits(), lane.waiting.get());
    return new HeavyRequestBusyException(lane.kind);
  }

  private static Permit permit(Lane lane) {
    AtomicBoolean held = new AtomicBoolean(true);
    return () -> {
      if (held.compareAndSet(true, false)) {
        lane.slots.release();
      }
    };
  }

  /** Slots of {@code kind} in use now. */
  public int inUse(HeavyKind kind) {
    Lane lane = lanes.get(kind);
    return lane.limit - lane.slots.availablePermits();
  }

  /** Web requests of {@code kind} waiting now. */
  public int waiting(HeavyKind kind) {
    return lanes.get(kind).waiting.get();
  }

  /** Busy answers of {@code kind} since start. */
  public long rejected(HeavyKind kind) {
    return lanes.get(kind).rejected.get();
  }

  /** Slots of {@code kind}. */
  public int limit(HeavyKind kind) {
    return lanes.get(kind).limit;
  }
}
