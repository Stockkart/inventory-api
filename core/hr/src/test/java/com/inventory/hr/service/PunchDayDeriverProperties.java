// Feature: hr-attendance-capture, Property 1: in = first counted, out = last counted, counted punches 5 min apart, input order irrelevant
// Feature: HR-module, Property 17: Day source and chips come from counted punches only
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.utils.HrDateUtils;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
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
 * <p>HR-module Property 17 (R8.1–R8.4, R8.7): for any shuffled list of punches with random My HR
 * / Attendance app sources, flags and times, {@code primarySource} is the source of the earliest
 * counted punch, {@code countedFlags} is exactly the union of the counted punches' flags (a flag
 * only a duplicate carries is absent), and duplicates never change the day's times, count,
 * direction or source.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.4, 8.1, 8.2, 8.3, 8.4, 8.7</b>
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

  @Property(tries = 200)
  void thePrimarySourceIsTheEarliestCountedPunchesAndCountedFlagsIgnoreDuplicates(
      @ForAll("secondsOfDay") List<Integer> seconds, @ForAll long seed) {
    Random random = new Random(seed);
    List<Punch> punches = punches(seconds);
    for (Punch p : punches) {
      p.setSource(random.nextBoolean() ? PunchSource.SELF : PunchSource.BIOMETRIC_APP);
      List<PunchFlag> flags = new ArrayList<>();
      for (PunchFlag f : PunchFlag.values()) {
        if (random.nextInt(4) == 0) {
          flags.add(f);
        }
      }
      p.setFlags(flags);
    }
    List<Punch> shuffled = new ArrayList<>(punches);
    Collections.shuffle(shuffled, new Random(seed + 1));

    PunchDayDeriver.Derived d = derive(shuffled);

    if (punches.isEmpty()) {
      assertNull(d.primarySource());
      assertTrue(d.countedFlags().isEmpty());
      return;
    }
    assertEquals(d.counted().get(0).getSource(), d.primarySource());
    // the earliest punch of the day always counts, so the icon is its source whatever came later
    Punch earliest = punches.stream().min(java.util.Comparator.comparing(Punch::getAt)
        .thenComparing(Punch::getId)).orElseThrow();
    assertEquals(earliest.getSource(), d.primarySource());
    Set<PunchFlag> expected = EnumSet.noneOf(PunchFlag.class);
    for (Punch c : d.counted()) {
      expected.addAll(c.getFlags());
    }
    assertEquals(expected, d.countedFlags());
  }

  // ---- HR-module Property 17: day source and chips come from counted punches only ----------

  /** Random SELF / BIOMETRIC_APP sources and random flag sets, decided by {@code seed}. */
  private static List<Punch> sourcedAndFlagged(List<Integer> seconds, long seed) {
    Random random = new Random(seed);
    List<Punch> punches = punches(seconds);
    for (Punch p : punches) {
      p.setSource(random.nextBoolean() ? PunchSource.SELF : PunchSource.BIOMETRIC_APP);
      p.setFlags(randomFlags(random));
    }
    return punches;
  }

  private static List<PunchFlag> randomFlags(Random random) {
    List<PunchFlag> flags = new ArrayList<>();
    for (PunchFlag f : PunchFlag.values()) {
      if (random.nextInt(3) == 0) {
        flags.add(f);
      }
    }
    return flags;
  }

  private static boolean isCounted(PunchDayDeriver.Derived d, Punch p) {
    return d.counted().stream().anyMatch(c -> c == p);
  }

  @Property(tries = 300)
  void countedFlagsAreExactlyTheUnionOverCountedPunchesAndDuplicateOnlyFlagsAreAbsent(
      @ForAll("secondsOfDay") List<Integer> seconds, @ForAll long seed) {
    List<Punch> punches = sourcedAndFlagged(seconds, seed);
    List<Punch> shuffled = new ArrayList<>(punches);
    Collections.shuffle(shuffled, new Random(seed ^ 0x5DEECE66DL));

    PunchDayDeriver.Derived d = derive(shuffled);

    if (punches.isEmpty()) {
      assertNull(d.primarySource());
      assertTrue(d.countedFlags().isEmpty());
      return;
    }
    // R8.1–R8.2: the icon is the source of the earliest counted punch, whatever came later
    Punch earliestCounted = d.counted().stream().min(java.util.Comparator
        .comparing(Punch::getAt).thenComparing(Punch::getId)).orElseThrow();
    assertEquals(earliestCounted.getSource(), d.primarySource());
    assertEquals(d.counted().get(0).getSource(), d.primarySource());

    // R8.4: a flag is on the day iff some counted punch carries it
    Set<PunchFlag> onCounted = EnumSet.noneOf(PunchFlag.class);
    Set<PunchFlag> onDuplicates = EnumSet.noneOf(PunchFlag.class);
    for (Punch p : punches) {
      (isCounted(d, p) ? onCounted : onDuplicates).addAll(p.getFlags());
    }
    assertEquals(onCounted, d.countedFlags());
    for (PunchFlag f : PunchFlag.values()) {
      assertEquals(onCounted.contains(f), d.countedFlags().contains(f), f.name());
    }
    // R8.7: a flag that only a duplicate carries is not shown on the day
    Set<PunchFlag> duplicateOnly = EnumSet.copyOf(onDuplicates);
    duplicateOnly.removeAll(onCounted);
    for (PunchFlag f : duplicateOnly) {
      assertFalse(d.countedFlags().contains(f), "duplicate-only flag shown: " + f);
    }
  }

  @Property(tries = 300)
  void duplicatesNeverAffectTimesCountDirectionOrSource(
      @ForAll("secondsOfDay") List<Integer> seconds, @ForAll long seed) {
    List<Punch> punches = sourcedAndFlagged(seconds, seed);
    PunchDayDeriver.Derived all = derive(punches);

    // 1. dropping every duplicate gives the same day
    List<Punch> countedOnly = new ArrayList<>();
    for (Punch p : punches) {
      if (isCounted(all, p)) {
        countedOnly.add(p);
      }
    }
    PunchDayDeriver.Derived withoutDuplicates = derive(countedOnly);
    assertSameDay(all, withoutDuplicates);

    // 2. changing every duplicate's source and flags (and its time, inside its 5-minute run)
    //    gives the same day: the direction (IN when empty, else OUT), in/out and icon are decided
    //    by the counted punches alone
    Random random = new Random(seed * 31 + 7);
    List<Punch> altered = new ArrayList<>();
    for (Punch p : punches) {
      Punch q = new Punch();
      q.setId(p.getId());
      q.setAt(p.getAt());
      q.setLocalDate(p.getLocalDate());
      if (isCounted(all, p)) {
        q.setSource(p.getSource());
        q.setFlags(new ArrayList<>(p.getFlags()));
      } else {
        q.setSource(p.getSource() == PunchSource.SELF ? PunchSource.BIOMETRIC_APP
            : PunchSource.SELF);
        q.setFlags(randomFlags(random));
        // still strictly inside the run: after the counted punch before it (never on the same
        // second, where the id tie-break would pick a different punch) and before the next
        // counted punch, so it stays a duplicate
        Instant previousCounted = all.counted().stream().map(Punch::getAt)
            .filter(t -> !t.isAfter(p.getAt())).max(Instant::compareTo).orElseThrow();
        long room = Duration.between(previousCounted, p.getAt()).toSeconds();
        q.setAt(room == 0 ? p.getAt() : previousCounted.plusSeconds(1 + random.nextLong(room)));
      }
      altered.add(q);
    }
    Collections.shuffle(altered, random);
    PunchDayDeriver.Derived changed = derive(altered);
    assertSameDay(all, changed);
    assertEquals(all.isEmpty(), changed.isEmpty(), "the next direction (IN when empty)");
  }

  private static void assertSameDay(PunchDayDeriver.Derived a, PunchDayDeriver.Derived b) {
    assertEquals(a.inTime(), b.inTime());
    assertEquals(a.outTime(), b.outTime());
    assertEquals(a.workedMinutes(), b.workedMinutes());
    assertEquals(a.punchCount(), b.punchCount());
    assertEquals(a.missingCheckOut(), b.missingCheckOut());
    assertEquals(a.primarySource(), b.primarySource());
    assertEquals(a.countedFlags(), b.countedFlags());
    assertEquals(a.counted().stream().map(Punch::getAt).toList(),
        b.counted().stream().map(Punch::getAt).toList());
  }

  @Provide
  Arbitrary<Integer> extra() {
    return Arbitraries.integers().between(0, 2 * 3600);
  }
}
