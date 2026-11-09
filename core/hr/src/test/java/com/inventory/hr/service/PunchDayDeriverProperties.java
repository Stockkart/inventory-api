// Feature: hr-attendance-capture, Property 1: in = first counted, out = last counted, counted punches 5 min apart, input order irrelevant
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Punches of one employee-day derive the same day whatever order they come in (design Testing,
 * {@code PunchDayDeriverProperties}): in-time is the first counted punch, out-time the last
 * counted one, no two consecutive counted punches are less than 5 minutes apart, every dropped
 * punch is within 5 minutes of the counted punch before it, and adding a punch never lowers
 * {@code punchCount} (the race guard's premise).
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.4</b>
 */
class PunchDayDeriverProperties {

  private static final LocalDate DAY = LocalDate.of(2025, 4, 8);
  private static final Instant START = DAY.atStartOfDay(HrDateUtils.ZONE).toInstant();

  @Provide
  Arbitrary<List<Integer>> secondsOfDay() {
    // clustered times (0–2 h) make duplicates common; 0–20 punches
    return Arbitraries.integers().between(0, 2 * 3600).list().ofMaxSize(20);
  }

  private static List<Punch> punches(List<Integer> seconds) {
    List<Punch> list = new ArrayList<>();
    for (int i = 0; i < seconds.size(); i++) {
      Punch p = new Punch();
      p.setId("p" + i);
      p.setAt(START.plusSeconds(seconds.get(i)));
      p.setLocalDate(HrDateUtils.format(DAY));
      list.add(p);
    }
    return list;
  }

  private static PunchDayDeriver.Derived derive(List<Punch> punches) {
    return PunchDayDeriver.derive(punches, DAY, DAY.plusDays(1), LocalTime.NOON,
        LocalTime.of(21, 0));
  }

  @Property(tries = 200)
  void inAndOutAreTheFirstAndLastCountedPunchAndCountedOnesAreFiveMinutesApart(
      @ForAll("secondsOfDay") List<Integer> seconds) {
    List<Punch> punches = punches(seconds);
    PunchDayDeriver.Derived d = derive(punches);
    List<Punch> counted = d.counted();

    assertEquals(counted.size(), d.punchCount());
    if (punches.isEmpty()) {
      assertTrue(counted.isEmpty());
      assertNull(d.inTime());
      return;
    }
    Instant earliest = punches.stream().map(Punch::getAt).min(Instant::compareTo).orElseThrow();
    assertEquals(earliest, counted.get(0).getAt(), "the earliest punch always counts");
    assertEquals(PunchDayDeriver.hhmm(counted.get(0).getAt()), d.inTime());
    if (counted.size() == 1) {
      assertNull(d.outTime());
      assertTrue(d.missingCheckOut(), "one punch on a past day");
    } else {
      assertEquals(PunchDayDeriver.hhmm(counted.get(counted.size() - 1).getAt()), d.outTime());
      assertFalse(d.missingCheckOut());
    }
    for (int i = 1; i < counted.size(); i++) {
      Duration gap = Duration.between(counted.get(i - 1).getAt(), counted.get(i).getAt());
      assertFalse(gap.compareTo(PunchDayDeriver.DUPLICATE_WINDOW) < 0,
          "counted punches less than 5 minutes apart: " + gap);
    }
    // every dropped punch is within 5 minutes after the counted punch before it
    for (Punch p : punches) {
      if (counted.stream().anyMatch(c -> c == p)) {
        continue;
      }
      Instant previous = counted.stream().map(Punch::getAt)
          .filter(t -> !t.isAfter(p.getAt())).max(Instant::compareTo).orElseThrow();
      assertTrue(Duration.between(previous, p.getAt()).compareTo(
          PunchDayDeriver.DUPLICATE_WINDOW) < 0, "a dropped punch must be a duplicate");
    }
  }

  @Property(tries = 200)
  void theOrderOfTheInputDoesNotMatter(@ForAll("secondsOfDay") List<Integer> seconds,
      @ForAll long seed) {
    List<Punch> punches = punches(seconds);
    List<Punch> shuffled = new ArrayList<>(punches);
    Collections.shuffle(shuffled, new Random(seed));

    PunchDayDeriver.Derived a = derive(punches);
    PunchDayDeriver.Derived b = derive(shuffled);

    assertEquals(a.inTime(), b.inTime());
    assertEquals(a.outTime(), b.outTime());
    assertEquals(a.punchCount(), b.punchCount());
    assertEquals(a.workedMinutes(), b.workedMinutes());
    assertEquals(a.counted().stream().map(Punch::getId).toList(),
        b.counted().stream().map(Punch::getId).toList());
  }

  @Property(tries = 200)
  void addingAPunchNeverLowersThePunchCount(@ForAll("secondsOfDay") List<Integer> seconds,
      @ForAll("extra") int extra) {
    List<Punch> punches = punches(seconds);
    int before = derive(punches).punchCount();
    List<Integer> more = new ArrayList<>(seconds);
    more.add(extra);

    assertTrue(derive(punches(more)).punchCount() >= before);
  }

  @Provide
  Arbitrary<Integer> extra() {
    return Arbitraries.integers().between(0, 2 * 3600);
  }
}
