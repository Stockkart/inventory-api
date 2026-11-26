// Feature: HR-module, Property 16: Hand-made mark always wins, for every punch source
// Feature: HR-module, Property 17: Day source and chips come from counted punches only
package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.utils.HrDateUtils;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/**
 * The derived-day writes against a real MongoDB, for any sequence of Attendance app punches, My
 * HR punches, hand-made marks (the daily screen's save and "Mark unmarked present") and cleared
 * marks on one employee-day, driven exactly as the services drive them and compared with a
 * simple model after every step.
 *
 * <p>Property 16 (R7.9): once a hand-made mark ({@code MANUAL} or {@code BULK_PRESENT}) is
 * stored, no later punch of any source, in any order (late offline punches included), changes the
 * day's status, times or source; after the mark is cleared, the day equals the derivation over all
 * punches of that day, or no row when there is none.
 *
 * <p>Property 17 (R8.1–R8.4, R8.7): a derived day's {@link AttendanceSource} is {@code
 * derivedSource(primarySource)} of the earliest counted punch, and {@code
 * countedFlagsByEmployeeDate} / {@code summaryByEmployeeDate} report exactly the deriver's counted
 * flags, source and punch count.
 *
 * <p>Runs against the throwaway MongoDB of the {@code *IT} tests: without {@code
 * -Dstockkart.test.mongo.uri} every try passes without doing anything (jqwik has no
 * system-property condition; see {@code SalaryPaymentProperties}).
 *
 * <p><b>Validates: Requirements 7.9, 8.1, 8.2, 8.3, 8.4, 8.7</b>
 */
class PunchDayServiceIT {

  private static final String URI = "stockkart.test.mongo.uri";
  private static final String USER = "manager-1";
  private static final AtomicInteger SHOPS = new AtomicInteger();
  private static final AtomicInteger PUNCHES = new AtomicInteger();
  private static final AttendanceStatus[] MARKS =
      {AttendanceStatus.PRESENT, AttendanceStatus.ABSENT, AttendanceStatus.HALF_DAY};
  private static final PunchFlag[] FLAGS = PunchFlag.values();
  private static HrMongoItSupport it;
  /** A past working day (never the default Sunday week-off), so a bulk mark can land on it. */
  private static LocalDate day;

  enum Kind { APP_PUNCH, SELF_PUNCH, MANUAL_MARK, BULK_MARK, CLEAR_MARK }

  /**
   * @param seconds time of a punch after midnight (0–2 h, so duplicates are common)
   * @param flagMask bit i = flag i of a punch
   * @param mark index into {@link #MARKS} for a hand-made mark
   */
  record Action(Kind kind, int seconds, int flagMask, int mark) {}

  @BeforeContainer
  static void setUp() {
    if (System.getProperty(URI) != null) {
      it = new HrMongoItSupport("punchday_props");
      LocalDate d = HrDateUtils.today().minusDays(1);
      while (d.getDayOfWeek() == DayOfWeek.SUNDAY) {
        d = d.minusDays(1);
      }
      day = d;
    }
  }

  @AfterContainer
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  @Provide
  Arbitrary<List<Action>> actions() {
    Arbitrary<Kind> kinds = Arbitraries.frequency(
        Tuple.of(30, Kind.APP_PUNCH), Tuple.of(25, Kind.SELF_PUNCH),
        Tuple.of(15, Kind.MANUAL_MARK), Tuple.of(15, Kind.BULK_MARK),
        Tuple.of(15, Kind.CLEAR_MARK));
    Arbitrary<Action> action = Combinators.combine(kinds,
        Arbitraries.integers().between(0, 2 * 3600),
        Arbitraries.integers().between(0, (1 << FLAGS.length) - 1),
        Arbitraries.integers().between(0, MARKS.length - 1)).as(Action::new);
    return action.list().ofMinSize(1).ofMaxSize(15);
  }

  @Property(tries = 100)
  void aHandMadeMarkAlwaysWinsAndDerivedDaysFollowTheCountedPunches(
      @ForAll("actions") List<Action> actions) {
    if (it == null) {
      return;
    }
    int n = SHOPS.incrementAndGet();
    String shop = "pd-shop-" + n;
    String date = HrDateUtils.format(day);
    Employee e = it.employee(shop, "EMP-" + n, "Worker " + n, null);
    HrSettings settings = it.settings.effectiveSettings(shop);

    // the model: the stored hand-made mark (null = none); a derived row, if any, is always what
    // the punches derive to, or absent after a clear until the next punch
    AttendanceDay mark = null;
    for (Action a : actions) {
      AttendanceDay before = stored(shop, e, date);
      switch (a.kind()) {
        case APP_PUNCH, SELF_PUNCH -> {
          PunchSource source =
              a.kind() == Kind.APP_PUNCH ? PunchSource.BIOMETRIC_APP : PunchSource.SELF;
          AttendanceDay written = punch(shop, e, date, settings, source, a);
          if (mark != null) {
            // R7.9: the hand-made mark is left exactly as it was, whatever the punch source
            assertNull(written, "writeDerived must not write over a hand-made mark: " + a);
            assertSameRow(mark, stored(shop, e, date), "after " + a + " in " + actions);
          } else {
            assertNotNull(written, "a punch without a hand-made mark derives the day: " + a);
          }
        }
        case MANUAL_MARK -> {
          AttendanceStatus status = MARKS[a.mark()];
          it.attendance.saveDays(USER, shop, save(e, date, status.name()));
          AttendanceDay now = stored(shop, e, date);
          assertNotNull(now);
          if (before != null && !AttendanceSource.isDerived(before.getSource())
              && before.getStatus() == status && before.getInTime() == null
              && before.getOutTime() == null && before.getNote() == null) {
            // the same hand-made mark again: nothing to write, the row stays as it was
            assertSameRow(before, now, "an unchanged hand-made mark: " + a);
          } else {
            // over a derived day even equal values make it a hand-made mark (R4.5)
            assertEquals(status, now.getStatus());
            assertEquals(AttendanceSource.MANUAL, now.getSource());
            assertNull(now.getPunchCount(), "a hand-made mark carries no punch fields");
            assertEquals(USER, now.getMarkedByUserId());
          }
          mark = now;
        }
        case BULK_MARK -> {
          it.attendance.markUnmarkedPresent(USER, shop,
              new MarkUnmarkedPresentRequest(List.of(date), List.of(e.getId())));
          AttendanceDay now = stored(shop, e, date);
          if (before == null) {
            assertNotNull(now, "an unmarked day is marked Present");
            assertEquals(AttendanceStatus.PRESENT, now.getStatus());
            assertEquals(AttendanceSource.BULK_PRESENT, now.getSource());
            assertNull(now.getPunchCount());
            mark = now;
          } else {
            // "Mark unmarked present" never overwrites a stored day, derived or hand-made
            assertSameRow(before, now, "bulk mark over a stored day: " + a);
          }
        }
        case CLEAR_MARK -> {
          it.attendance.saveDays(USER, shop, save(e, date, "UNMARKED"));
          if (mark != null) {
            // R7.9: the cleared hand-made mark gives way to the derivation over all punches
            mark = null;
          } else {
            // a cleared derived day stays cleared until the next punch
            assertNull(stored(shop, e, date), "a cleared derived day stays cleared: " + a);
          }
        }
      }
      check(shop, e, date, settings, mark, actions);
    }
  }

  // ---- the model check after every step ----------------------------------------------------

  private static void check(String shop, Employee e, String date, HrSettings settings,
      AttendanceDay mark, List<Action> actions) {
    List<Punch> punches = it.punchDays.punchesOf(shop, e.getId(), date);
    PunchDayDeriver.Derived derived = PunchDayService.derive(punches, e, date, settings);
    AttendanceDay now = stored(shop, e, date);
    String context = " (" + actions + ")";

    if (mark != null) {
      assertSameRow(mark, now, "the hand-made mark" + context);
    } else if (now != null) {
      // a derived row is exactly what all punches of the day derive to (R8.1–R8.2)
      assertTrue(AttendanceSource.isDerived(now.getSource()), "derived row" + context);
      assertEquals(PunchDayService.derivedSource(derived.primarySource()), now.getSource(),
          "day source = source of the earliest counted punch" + context);
      assertEquals(derived.inTime(), now.getInTime(), context);
      assertEquals(derived.outTime(), now.getOutTime(), context);
      assertEquals(derived.workedMinutes(), now.getWorkedMinutes(), context);
      assertEquals(derived.punchCount(), now.getPunchCount(), context);
      boolean half = DayStatusResolver.isAutoHalfDay(AttendanceStatus.PRESENT,
          derived.workedMinutes(), settings);
      assertEquals(half ? AttendanceStatus.HALF_DAY : AttendanceStatus.PRESENT, now.getStatus(),
          context);
      assertNull(now.getMarkedByUserId(), context);
    }

    // R8.3–R8.4, R8.7: the register reads report the counted punches only
    String key = AttendanceService.key(e.getId(), date);
    Map<String, Set<PunchFlag>> flags = it.punchDays.countedFlagsByEmployeeDate(shop,
        List.of(e.getId()), date, HrQueryRunner.SCREEN_MAX_TIME_MS);
    Set<PunchFlag> expectedFlags = derived.countedFlags();
    if (expectedFlags.isEmpty()) {
      assertNull(flags.get(key), "days without counted flags have no entry" + context);
    } else {
      assertEquals(expectedFlags, flags.get(key), context);
    }
    Set<PunchFlag> duplicateOnly = EnumSet.noneOf(PunchFlag.class);
    for (Punch p : punches) {
      if (derived.counted().stream().noneMatch(c -> c.getId().equals(p.getId()))) {
        duplicateOnly.addAll(p.getFlags());
      }
    }
    duplicateOnly.removeAll(expectedFlags);
    for (PunchFlag f : duplicateOnly) {
      assertTrue(flags.get(key) == null || !flags.get(key).contains(f),
          "a flag only a duplicate carries is not shown: " + f + context);
    }
    Map<String, PunchDayService.DaySummary> summaries = it.punchDays.summaryByEmployeeDate(shop,
        List.of(e.getId()), date, HrQueryRunner.SCREEN_MAX_TIME_MS);
    if (punches.isEmpty()) {
      assertNull(summaries.get(key), context);
    } else {
      PunchDayService.DaySummary s = summaries.get(key);
      assertNotNull(s, context);
      assertEquals(derived.primarySource(), s.primarySource(), context);
      assertEquals(expectedFlags, s.countedFlags(), context);
      assertEquals(punches.size(), s.punchCount(), "every punch, duplicates included" + context);
    }
  }

  // ---- driving the services ----------------------------------------------------------------

  /** Stores a punch and re-derives the day, as the app and My HR punch paths do. */
  private static AttendanceDay punch(String shop, Employee e, String date, HrSettings settings,
      PunchSource source, Action a) {
    Punch p = new Punch();
    p.setShopId(shop);
    p.setEmployeeId(e.getId());
    p.setAt(day.atStartOfDay(HrDateUtils.ZONE).toInstant().plusSeconds(a.seconds()));
    p.setLocalDate(date);
    p.setSource(source);
    List<PunchFlag> flags = new ArrayList<>();
    for (int i = 0; i < FLAGS.length; i++) {
      if ((a.flagMask() & (1 << i)) != 0) {
        flags.add(FLAGS[i]);
      }
    }
    p.setFlags(flags);
    p.setDedupeKey((source == PunchSource.BIOMETRIC_APP ? "B:" : "S:") + e.getId() + ":"
        + PUNCHES.incrementAndGet());
    p.setCreatedAt(Instant.now());
    it.template.insert(p);
    List<Punch> all = it.punchDays.punchesOf(shop, e.getId(), date);
    return it.punchDays.writeDerived(shop, e, date, settings,
        PunchDayService.derive(all, e, date, settings));
  }

  private static SaveAttendanceDaysRequest save(Employee e, String date, String status) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(
        new SaveAttendanceDaysRequest.Row(e.getId(), date, status, null, null, null))));
  }

  private static AttendanceDay stored(String shop, Employee e, String date) {
    return it.template.findOne(Query.query(Criteria.where("shopId").is(shop)
        .and("employeeId").is(e.getId()).and("date").is(date)), AttendanceDay.class);
  }

  /** The fields a punch could change: status, times, source, count and who marked it. */
  private static void assertSameRow(AttendanceDay expected, AttendanceDay actual, String what) {
    assertNotNull(actual, what);
    assertEquals(expected.getId(), actual.getId(), what);
    assertEquals(expected.getStatus(), actual.getStatus(), what);
    assertEquals(expected.getInTime(), actual.getInTime(), what);
    assertEquals(expected.getOutTime(), actual.getOutTime(), what);
    assertEquals(expected.getWorkedMinutes(), actual.getWorkedMinutes(), what);
    assertEquals(expected.getAutoHalfDay(), actual.getAutoHalfDay(), what);
    assertEquals(expected.getSource(), actual.getSource(), what);
    assertEquals(expected.getPunchCount(), actual.getPunchCount(), what);
    assertEquals(expected.getMissingCheckOut(), actual.getMissingCheckOut(), what);
    assertEquals(expected.getMarkedAt(), actual.getMarkedAt(), what);
    assertEquals(expected.getMarkedByUserId(), actual.getMarkedByUserId(), what);
  }
}
