package com.inventory.common.heavy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Slots, the waiting line, waits, background work and settings of {@link HeavyRequestLimiter}. */
class HeavyRequestLimiterTest {

  private final ExecutorService pool = Executors.newCachedThreadPool();

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
    RequestContextHolder.resetRequestAttributes();
  }

  private static HeavyRequestLimiter limiter(String... props) {
    MockEnvironment env = new MockEnvironment();
    for (int i = 0; i < props.length; i += 2) {
      env.setProperty(props[i], props[i + 1]);
    }
    return new HeavyRequestLimiter(env);
  }

  /** Runs {@code work} on another thread as if inside a web request. */
  private Future<?> asWebRequest(Runnable work) {
    return pool.submit(() -> {
      RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
      try {
        work.run();
      } finally {
        RequestContextHolder.resetRequestAttributes();
      }
    });
  }

  private static void waitUntil(java.util.function.BooleanSupplier ok) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!ok.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("condition not met in 5 s");
      }
      Thread.sleep(5);
    }
  }

  @Test
  void defaultsAreTheMeasuredValues() {
    HeavyRequestLimiter l = limiter();
    assertThat(l.limit(HeavyKind.EXPORT)).isEqualTo(1);
    assertThat(l.limit(HeavyKind.ANALYTICS)).isEqualTo(3);
    assertThat(l.limit(HeavyKind.OCR)).isEqualTo(2);
    assertThat(l.limit(HeavyKind.IMPORT)).isEqualTo(1);
    assertThat(l.limit(HeavyKind.HR)).isEqualTo(2);
  }

  @Test
  void settingsOverrideTheDefaults() {
    HeavyRequestLimiter l = limiter("heavy.export.limit", "2", "heavy.analytics.limit", "5");
    assertThat(l.limit(HeavyKind.EXPORT)).isEqualTo(2);
    assertThat(l.limit(HeavyKind.ANALYTICS)).isEqualTo(5);
  }

  @Test
  void aSecondRequestWaitsForTheSlotThenRuns() throws Exception {
    HeavyRequestLimiter l = limiter("heavy.export.wait-ms", "5000");
    HeavyRequestLimiter.Permit first = l.acquire(HeavyKind.EXPORT);
    CountDownLatch ran = new CountDownLatch(1);
    Future<?> second = asWebRequest(() -> {
      try (HeavyRequestLimiter.Permit p = l.acquire(HeavyKind.EXPORT)) {
        ran.countDown();
      }
    });
    waitUntil(() -> l.waiting(HeavyKind.EXPORT) == 1);
    assertThat(ran.getCount()).isEqualTo(1); // still waiting while the first holds the slot
    first.close();
    second.get(5, TimeUnit.SECONDS);
    assertThat(ran.getCount()).isZero();
    assertThat(l.inUse(HeavyKind.EXPORT)).isZero();
    assertThat(l.waiting(HeavyKind.EXPORT)).isZero();
    assertThat(l.rejected(HeavyKind.EXPORT)).isZero();
  }

  @Test
  void aFullLineIsAnsweredBusyAtOnce() throws Exception {
    HeavyRequestLimiter l = limiter("heavy.export.queue", "2", "heavy.export.wait-ms", "5000");
    HeavyRequestLimiter.Permit held = l.acquire(HeavyKind.EXPORT);
    List<Future<?>> waiters = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      waiters.add(asWebRequest(() -> l.acquire(HeavyKind.EXPORT).close()));
    }
    waitUntil(() -> l.waiting(HeavyKind.EXPORT) == 2);

    long t0 = System.nanoTime();
    Future<?> third = asWebRequest(() -> l.acquire(HeavyKind.EXPORT));
    assertThatThrownBy(() -> third.get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(HeavyRequestBusyException.class);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(1000); // no wait
    assertThat(l.rejected(HeavyKind.EXPORT)).isEqualTo(1);

    held.close();
    for (Future<?> w : waiters) {
      w.get(5, TimeUnit.SECONDS); // the two in line still got their turn
    }
  }

  @Test
  void waitingTooLongIsAnsweredBusyWithTheKindsMessage() throws Exception {
    HeavyRequestLimiter l = limiter("heavy.analytics.limit", "1", "heavy.analytics.wait-ms", "150");
    HeavyRequestLimiter.Permit held = l.acquire(HeavyKind.ANALYTICS);
    Future<?> late = asWebRequest(() -> l.acquire(HeavyKind.ANALYTICS));
    assertThatThrownBy(() -> late.get(5, TimeUnit.SECONDS))
        .cause()
        .isInstanceOf(HeavyRequestBusyException.class)
        .hasMessage(HeavyKind.ANALYTICS.busyMessage);
    assertThat(l.waiting(HeavyKind.ANALYTICS)).isZero();
    held.close();
  }

  @Test
  void requestsGetTheSlotInArrivalOrder() throws Exception {
    HeavyRequestLimiter l = limiter("heavy.export.queue", "5", "heavy.export.wait-ms", "5000");
    HeavyRequestLimiter.Permit held = l.acquire(HeavyKind.EXPORT);
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    List<Future<?>> all = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      int n = i;
      all.add(asWebRequest(() -> {
        try (HeavyRequestLimiter.Permit p = l.acquire(HeavyKind.EXPORT)) {
          order.add(n);
        }
      }));
      int expect = i + 1;
      waitUntil(() -> l.waiting(HeavyKind.EXPORT) == expect);
    }
    held.close();
    for (Future<?> f : all) {
      f.get(5, TimeUnit.SECONDS);
    }
    assertThat(order).containsExactly(0, 1, 2, 3);
  }

  @Test
  void backgroundWorkIsNotInTheLineAndWaitsItsOwnTime() throws Exception {
    HeavyRequestLimiter l = limiter("heavy.ocr.limit", "1", "heavy.ocr.queue", "0",
        "heavy.ocr.wait-ms", "50", "heavy.ocr.background-wait-ms", "5000");
    HeavyRequestLimiter.Permit held = l.acquire(HeavyKind.OCR);
    // a web request with no room in the line: busy at once
    assertThatThrownBy(() -> asWebRequest(() -> l.acquire(HeavyKind.OCR)).get(5, TimeUnit.SECONDS))
        .hasCauseInstanceOf(HeavyRequestBusyException.class);
    // background work (no request on its thread) waits past the web wait and then runs
    CountDownLatch ran = new CountDownLatch(1);
    Future<?> background = pool.submit(() -> {
      try (HeavyRequestLimiter.Permit p = l.acquire(HeavyKind.OCR)) {
        ran.countDown();
      }
    });
    Thread.sleep(300);
    assertThat(ran.getCount()).isEqualTo(1);
    assertThat(l.waiting(HeavyKind.OCR)).isZero(); // not counted in the web line
    held.close();
    background.get(5, TimeUnit.SECONDS);
    assertThat(ran.getCount()).isZero();
  }

  @Test
  void theSlotComesBackOnErrorsAndOnlyOnce() {
    HeavyRequestLimiter l = limiter("heavy.import.limit", "1");
    assertThatThrownBy(() -> l.run(HeavyKind.IMPORT, () -> {
      throw new IllegalStateException("boom");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(l.inUse(HeavyKind.IMPORT)).isZero();

    HeavyRequestLimiter.Permit p = l.acquire(HeavyKind.IMPORT);
    p.close();
    p.close(); // a second close must not free a slot it does not hold
    HeavyRequestLimiter.Permit again = l.acquire(HeavyKind.IMPORT);
    assertThat(l.inUse(HeavyKind.IMPORT)).isEqualTo(1);
    again.close();
  }

  @Test
  void kindsDoNotShareSlots() {
    HeavyRequestLimiter l = limiter();
    try (HeavyRequestLimiter.Permit export = l.acquire(HeavyKind.EXPORT);
        HeavyRequestLimiter.Permit imp = l.acquire(HeavyKind.IMPORT)) {
      assertThat(l.inUse(HeavyKind.EXPORT)).isEqualTo(1);
      assertThat(l.inUse(HeavyKind.IMPORT)).isEqualTo(1);
      assertThat(l.inUse(HeavyKind.ANALYTICS)).isZero();
    }
  }
}
