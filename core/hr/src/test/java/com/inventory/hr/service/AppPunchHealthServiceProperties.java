// Feature: HR-module, Property 22: Signature-failure alert window
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.hr.domain.model.AppHealthAlert;
import com.inventory.hr.domain.repository.AppHealthAlertRepository;
import com.inventory.hr.service.AppPunchHealthServiceTest.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property 22 of the HR-module design (task 7.5), run against the real {@link
 * AppPunchHealthService} over a pinned {@link MutableClock} and an in-memory stand-in for {@code
 * hr_app_alerts}.
 *
 * <p><b>Validates: Requirements 10.4, 10.5, 10.6.</b> For any random sequence of punch attempts
 * (minute offset over 0–180 minutes, shop, failed or not) interleaved with recalculations at
 * arbitrary minutes, an independent model of the rule predicts the service's state after every
 * recalculation:
 *
 * <ul>
 *   <li>the window for a shop is the attempts whose minute lies in {@code (now − 60, now]} (bucket
 *       granularity, the implementation's and R10.4's rolling hour); {@code totals()} equals it;
 *   <li>an alert is open for a shop iff one was raised and not yet cleared; one is raised at a
 *       recalculation iff none is open, the window has at least 20 attempts and {@code failures /
 *       attempts > 0.01} (R10.5) — never with fewer than 20 attempts, never a second open row for
 *       the same shop — and the row records the window's rate, failures and attempts at that time;
 *   <li>an open alert is cleared at a recalculation iff the window rate is {@code <= 0.01} and a
 *       full 60 minutes have passed both since {@code raisedAt} and since the instance started
 *       observing (R10.6 as implemented: {@code windowIsFull}, so the first hour after a restart
 *       never clears an alert raised earlier, and a shop that went quiet clears once its hour is
 *       up); {@code clearedAt} is the recalculation's time;
 *   <li>a second recalculation at the same instant changes nothing (idempotent);
 *   <li>{@link AppPunchHealthService#record} never throws, for any shop id including null and
 *       blank, and null or blank ids create no window.
 * </ul>
 */
class AppPunchHealthServiceProperties {

  private static final Instant T0 = Instant.parse("2025-04-03T09:00:00Z");
  private static final List<String> SHOPS = List.of("shop-a", "shop-b", "shop-c");

  /** One event of the timeline: a punch attempt or a recalculation, at {@code minute}. */
  record Event(int minute, int shop, boolean failed, boolean recalculate) {}

  @Provide
  Arbitrary<List<Event>> timelines() {
    // attempts: failures are weighted so that 1 % is crossed from both sides; shops 0–2
    Arbitrary<Event> attempt = Combinators.combine(
        Arbitraries.integers().between(0, 180),
        Arbitraries.integers().between(0, SHOPS.size() - 1),
        Arbitraries.frequencyOf(
            Tuple.of(30, Arbitraries.just(false)),
            Tuple.of(1, Arbitraries.just(true))))
        .as((m, s, f) -> new Event(m, s, f, false));
    Arbitrary<Event> recalc = Arbitraries.integers().between(0, 240)
        .map(m -> new Event(m, 0, false, true));
    Arbitrary<Event> event = Arbitraries.frequencyOf(
        Tuple.of(8, attempt),
        Tuple.of(1, recalc));
    return event.list().ofMinSize(1).ofMaxSize(400)
        .map(list -> list.stream().sorted(Comparator.comparingInt(Event::minute)).toList());
  }

  /** An alert raised long before the instance started, as after a restart; or none. */
  @Provide
  Arbitrary<Integer> oldAlertHoursAgo() {
    return Arbitraries.frequencyOf(
        Tuple.of(2, Arbitraries.just(-1)),
        Tuple.of(1, Arbitraries.integers().between(0, 6)));
  }

  @Property(tries = 200)
  void alertsFollowTheWindowRule(@ForAll("timelines") List<Event> events,
      @ForAll("oldAlertHoursAgo") int oldAlertHoursAgo) {
    MutableClock clock = new MutableClock(T0);
    Store store = new Store();
    AppPunchHealthService service = new AppPunchHealthService(store.repository, mock(
        HrPermissionGuard.class), clock);
    Model model = new Model(T0);
    if (oldAlertHoursAgo >= 0) {
      Instant raisedAt = T0.minus(Duration.ofHours(oldAlertHoursAgo));
      store.rows.add(new AppHealthAlert("alert-old", SHOPS.get(0), 0.2, 4, 20, raisedAt, null));
      model.open.put(SHOPS.get(0), new Model.Open(raisedAt, 4, 20));
      model.rowsRaised = 1;
    }

    int current = 0;
    for (Event e : events) {
      if (e.minute() > current) {
        clock.advance(Duration.ofMinutes(e.minute() - current));
        current = e.minute();
      }
      Instant now = clock.instant();
      if (!e.recalculate()) {
        String shop = SHOPS.get(e.shop());
        service.record(shop, e.failed());
        model.record(shop, current, e.failed());
        continue;
      }
      // the window the service sees must be the model's
      for (String shop : SHOPS) {
        Model.Totals t = model.totals(shop, current);
        assertEquals(new AppPunchHealthService.Totals(t.attempts(), t.failures()),
            service.totals(shop), "window of " + shop + " at minute " + current);
      }

      List<Model.Change> expected = model.recalculate(now, current);
      service.recalculate();
      String context = "recalculation at minute " + current + " (" + now + ")";

      assertEquals(model.openShops(), store.openShops(), context + ": open alerts");
      for (Model.Change c : expected) {
        AppHealthAlert row = store.latest(c.shop());
        assertNotNull(row, context + ": row for " + c.shop());
        if (c.raised()) {
          assertNull(row.getClearedAt(), context);
          assertEquals(now, row.getRaisedAt(), context + ": raisedAt of " + c.shop());
          assertEquals(c.attempts(), row.getAttempts(), context + ": attempts of " + c.shop());
          assertEquals(c.failures(), row.getFailed(), context + ": failed of " + c.shop());
          assertEquals((double) c.failures() / c.attempts(), row.getRate(), 1e-12,
              context + ": rate of " + c.shop());
          assertTrue(c.attempts() >= AppPunchHealthService.MIN_ATTEMPTS, context);
          assertTrue(row.getRate() > AppPunchHealthService.MAX_RATE, context);
        } else {
          assertEquals(now, row.getClearedAt(), context + ": clearedAt of " + c.shop());
        }
      }
      // never two open rows for one shop, and rows are only ever appended or cleared
      for (String shop : SHOPS) {
        assertTrue(store.rows.stream().filter(r -> shop.equals(r.getShopId())
            && r.getClearedAt() == null).count() <= 1, context + ": one open row for " + shop);
      }
      assertEquals(model.rowsRaised(), store.rows.size(), context + ": rows");

      // idempotent: the same instant again changes nothing
      List<String> snapshot = store.snapshot();
      service.recalculate();
      assertEquals(snapshot, store.snapshot(), context + ": second run must change nothing");
    }
  }

  @Property(tries = 100)
  void anAlertIsNeverRaisedWithFewerThanTwentyAttempts(
      @ForAll("fewAttempts") List<Tuple.Tuple2<Integer, Boolean>> attempts) {
    MutableClock clock = new MutableClock(T0);
    Store store = new Store();
    AppPunchHealthService service =
        new AppPunchHealthService(store.repository, mock(HrPermissionGuard.class), clock);
    int current = 0;
    for (Tuple.Tuple2<Integer, Boolean> a : attempts) {
      if (a.get1() > current) {
        clock.advance(Duration.ofMinutes(a.get1() - current));
        current = a.get1();
      }
      service.record(SHOPS.get(0), a.get2());
      service.recalculate();
    }
    service.recalculate();
    assertTrue(store.rows.isEmpty(), "raised with " + attempts.size() + " attempts in total");
  }

  @Provide
  Arbitrary<List<Tuple.Tuple2<Integer, Boolean>>> fewAttempts() {
    return Combinators.combine(Arbitraries.integers().between(0, 90), Arbitraries.of(true, false))
        .as((m, f) -> Tuple.of(m, f)).list().ofMaxSize(AppPunchHealthService.MIN_ATTEMPTS - 1)
        .map(l -> l.stream().sorted(Comparator.comparingInt(t -> t.get1())).toList());
  }

  @Property(tries = 200)
  void recordNeverThrowsAndBlankShopsCreateNoWindow(@ForAll("anyShopId") String shopId,
      @ForAll boolean failed) {
    MutableClock clock = new MutableClock(T0);
    Store store = new Store();
    AppPunchHealthService service =
        new AppPunchHealthService(store.repository, mock(HrPermissionGuard.class), clock);

    service.record(shopId, failed);
    service.record(shopId, !failed);

    // blank as the service means it (Character.isWhitespace, like String.isBlank), so a control
    // character such as NUL is an odd but real id
    boolean blank = shopId == null || shopId.isBlank();
    assertEquals(blank ? 0 : 1, service.size(), "windows after recording " + show(shopId));
    if (!blank) {
      assertEquals(new AppPunchHealthService.Totals(2, 1), service.totals(shopId));
    }
    service.recalculate();
    assertFalse(store.rows.stream().anyMatch(r -> r.getShopId() == null), "no row without a shop");
  }

  @Provide
  Arbitrary<String> anyShopId() {
    return Arbitraries.frequencyOf(
        Tuple.of(1, Arbitraries.just((String) null)),
        Tuple.of(1, Arbitraries.strings().withChars(' ', '\t', '\n', '\r').ofMaxLength(4)),
        Tuple.of(1, Arbitraries.strings().ofMinLength(1).ofMaxLength(40)),
        Tuple.of(1, Arbitraries.strings().withCharRange('0', '9').withCharRange('a', 'f')
            .ofLength(24)));
  }

  // ---- model ---------------------------------------------------------------------------------

  /** The rule of R10.4–R10.6 written independently of the ring buffer. */
  static final class Model {
    record Attempt(int minute, boolean failed) {}
    record Totals(int attempts, int failures) {}
    record Open(Instant raisedAt, int failures, int attempts) {}
    /** What one recalculation did to one shop: raised with these numbers, or cleared. */
    record Change(String shop, boolean raised, int attempts, int failures) {}

    final Instant startedAt;
    final Map<String, List<Attempt>> attempts = new HashMap<>();
    final Map<String, Open> open = new HashMap<>();
    int rowsRaised = 0;

    Model(Instant startedAt) {
      this.startedAt = startedAt;
    }

    void record(String shop, int minute, boolean failed) {
      attempts.computeIfAbsent(shop, k -> new ArrayList<>()).add(new Attempt(minute, failed));
    }

    Totals totals(String shop, int nowMinute) {
      int a = 0;
      int f = 0;
      for (Attempt at : attempts.getOrDefault(shop, List.of())) {
        int age = nowMinute - at.minute();
        if (age >= 0 && age < AppPunchHealthService.WINDOW_MINUTES) {
          a++;
          if (at.failed()) {
            f++;
          }
        }
      }
      return new Totals(a, f);
    }

    List<Change> recalculate(Instant now, int nowMinute) {
      List<Change> changes = new ArrayList<>();
      Set<String> shops = new LinkedHashSet<>(attempts.keySet());
      shops.addAll(open.keySet());
      for (String shop : shops) {
        Totals t = totals(shop, nowMinute);
        double rate = t.attempts() == 0 ? 0d : (double) t.failures() / t.attempts();
        Open o = open.get(shop);
        if (o == null) {
          if (t.attempts() >= AppPunchHealthService.MIN_ATTEMPTS
              && rate > AppPunchHealthService.MAX_RATE) {
            open.put(shop, new Open(now, t.failures(), t.attempts()));
            rowsRaised++;
            changes.add(new Change(shop, true, t.attempts(), t.failures()));
          }
          continue;
        }
        Instant since = o.raisedAt().isAfter(startedAt) ? o.raisedAt() : startedAt;
        boolean full = !since.plus(AppPunchHealthService.WINDOW).isAfter(now);
        if (rate <= AppPunchHealthService.MAX_RATE && full) {
          open.remove(shop);
          changes.add(new Change(shop, false, t.attempts(), t.failures()));
        }
      }
      return changes;
    }

    Set<String> openShops() {
      return new LinkedHashSet<>(open.keySet());
    }

    /** Rows the store should hold: one per raise, plus the pre-existing one when seeded. */
    int rowsRaised() {
      return rowsRaised;
    }
  }

  /** {@code hr_app_alerts} in memory, behind a Mockito double of the repository. */
  static final class Store {
    final List<AppHealthAlert> rows = new ArrayList<>();
    final AppHealthAlertRepository repository = mock(AppHealthAlertRepository.class);

    Store() {
      when(repository.save(any())).thenAnswer(inv -> {
        AppHealthAlert a = inv.getArgument(0);
        if (a.getId() == null) {
          a.setId("alert-" + (rows.size() + 1));
          rows.add(a);
        }
        return a;
      });
      when(repository.findByClearedAtIsNull())
          .thenAnswer(inv -> rows.stream().filter(a -> a.getClearedAt() == null).toList());
      when(repository.findByShopIdAndClearedAtIsNull(anyString())).thenAnswer(inv -> rows.stream()
          .filter(a -> a.getClearedAt() == null && inv.getArgument(0).equals(a.getShopId()))
          .findFirst());
    }

    Set<String> openShops() {
      Set<String> s = new LinkedHashSet<>();
      for (AppHealthAlert a : rows) {
        if (a.getClearedAt() == null) {
          s.add(a.getShopId());
        }
      }
      return s;
    }

    /** The newest row of the shop (the one a raise or clear just touched). */
    AppHealthAlert latest(String shop) {
      AppHealthAlert found = null;
      for (AppHealthAlert a : rows) {
        if (shop.equals(a.getShopId())) {
          found = a;
        }
      }
      return found;
    }

    List<String> snapshot() {
      return rows.stream().map(a -> a.getId() + "|" + a.getShopId() + "|" + a.getRaisedAt() + "|"
          + a.getClearedAt() + "|" + a.getRate() + "|" + a.getFailed() + "|" + a.getAttempts())
          .toList();
    }
  }

  private static String show(String s) {
    return s == null ? "null" : "\"" + s + "\"";
  }
}
