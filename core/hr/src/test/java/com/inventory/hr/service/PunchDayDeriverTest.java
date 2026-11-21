package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.utils.HrDateUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PunchDayDeriverTest {

  private static final LocalDate DAY = LocalDate.of(2025, 4, 8);
  private static final LocalTime END = LocalTime.of(21, 0);

  static Punch at(String id, LocalDate date, String hhmm) {
    Punch p = new Punch();
    p.setId(id);
    p.setAt(LocalDateTime.of(date, LocalTime.parse(hhmm)).atZone(HrDateUtils.ZONE).toInstant());
    p.setLocalDate(HrDateUtils.format(date));
    p.setSource(PunchSource.SELF);
    return p;
  }

  static Punch app(String id, LocalDate date, String hhmm, PunchFlag... flags) {
    Punch p = at(id, date, hhmm);
    p.setSource(PunchSource.BIOMETRIC_APP);
    p.setFlags(new ArrayList<>(Arrays.asList(flags)));
    return p;
  }

  private static PunchDayDeriver.Derived derive(LocalDate today, LocalTime now, Punch... punches) {
    return PunchDayDeriver.derive(new ArrayList<>(Arrays.asList(punches)), DAY, today, now, END);
  }

  @Test
  void noPunchesGiveAnEmptyDay() {
    PunchDayDeriver.Derived d = derive(DAY.plusDays(1), LocalTime.NOON);

    assertTrue(d.isEmpty());
    assertNull(d.inTime());
    assertNull(d.outTime());
    assertEquals(0, d.punchCount());
    assertFalse(d.missingCheckOut());
    assertNull(d.primarySource());
    assertTrue(d.countedFlags().isEmpty());
  }

  @Test
  void thePrimarySourceIsThatOfTheEarliestCountedPunchWhateverTheInputOrder() {
    // R8.2: My HR at 09:00, app at 13:00 → phone icon; the reverse → fingerprint icon
    PunchDayDeriver.Derived selfFirst = derive(DAY, LocalTime.NOON,
        app("b", DAY, "13:00"), at("a", DAY, "09:00"));
    PunchDayDeriver.Derived appFirst = derive(DAY, LocalTime.NOON,
        at("b", DAY, "13:00"), app("a", DAY, "09:00"));

    assertEquals(PunchSource.SELF, selfFirst.primarySource());
    assertEquals(PunchSource.BIOMETRIC_APP, appFirst.primarySource());
  }

  @Test
  void aDuplicateAppPunchDoesNotChangeThePrimarySource() {
    // 09:00 SELF counts; the app punch at 09:03 is a duplicate of it (R8.7)
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        at("a", DAY, "09:00"), app("b", DAY, "09:03"), app("c", DAY, "18:00"));

    assertEquals(PunchSource.SELF, d.primarySource());
    assertEquals(List.of("a", "c"), d.counted().stream().map(Punch::getId).toList());
  }

  @Test
  void countedFlagsLeaveOutTheFlagsOfDuplicatePunches() {
    // R8.4, R8.7: OUTSIDE_SHOP only on the 09:02 duplicate → not a chip; RECORDED_OFFLINE on a
    // counted punch → chip
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        app("a", DAY, "09:00"), app("b", DAY, "09:02", PunchFlag.OUTSIDE_SHOP),
        app("c", DAY, "18:00", PunchFlag.RECORDED_OFFLINE));

    assertEquals(Set.of(PunchFlag.RECORDED_OFFLINE), d.countedFlags());
  }

  @Test
  void countedFlagsAreTheUnionOverCountedPunches() {
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        app("a", DAY, "09:00", PunchFlag.FAKE_LOCATION),
        app("b", DAY, "18:00", PunchFlag.RECORDED_OFFLINE, PunchFlag.LOW_ACCURACY));

    assertEquals(Set.of(PunchFlag.FAKE_LOCATION, PunchFlag.RECORDED_OFFLINE,
        PunchFlag.LOW_ACCURACY), d.countedFlags());
  }

  @Test
  void inIsTheFirstAndOutTheLastCountedPunchInIndianTime() {
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        at("c", DAY, "18:30"), at("a", DAY, "09:12"), at("b", DAY, "13:00"));

    assertEquals("09:12", d.inTime());
    assertEquals("18:30", d.outTime());
    assertEquals(3, d.punchCount());
    assertEquals(558, d.workedMinutes());
    assertFalse(d.missingCheckOut());
  }

  @Test
  void aPunchWithinFiveMinutesOfThePreviousCountedOneIsADuplicate() {
    // 09:00 counted; 09:04 duplicate; 09:08 is 8 min after 09:00, so counted; 09:12:59 dup
    Punch late = at("d", DAY, "09:12");
    late.setAt(late.getAt().plusSeconds(59));
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        at("a", DAY, "09:00"), at("b", DAY, "09:04"), at("c", DAY, "09:08"), late);

    assertEquals(2, d.punchCount());
    assertEquals(List.of("a", "c"), d.counted().stream().map(Punch::getId).toList());
    assertEquals("09:08", d.outTime());
  }

  @Test
  void exactlyFiveMinutesLaterCounts() {
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON,
        at("a", DAY, "09:00"), at("b", DAY, "09:05"));

    assertEquals(2, d.punchCount());
  }

  @Test
  void oneCountedPunchHasNoOutTime() {
    PunchDayDeriver.Derived d = derive(DAY, LocalTime.NOON, at("a", DAY, "09:00"),
        at("b", DAY, "09:02"));

    assertEquals("09:00", d.inTime());
    assertNull(d.outTime());
    assertNull(d.workedMinutes());
    assertEquals(1, d.punchCount());
  }

  @Test
  void oneCountedPunchMissesTheCheckOutOnceTheDayIsOverOrAfterWorkEnd() {
    Punch only = at("a", DAY, "09:00");

    assertFalse(derive(DAY, LocalTime.of(20, 59), only).missingCheckOut(), "before work end");
    assertTrue(derive(DAY, END, only).missingCheckOut(), "at work end");
    assertTrue(derive(DAY.plusDays(1), LocalTime.of(0, 1), only).missingCheckOut(), "next day");
    assertFalse(derive(DAY.plusDays(1), LocalTime.NOON, only, at("b", DAY, "18:00"))
        .missingCheckOut(), "checked out");
  }

  @Test
  void withoutAWorkEndOnlyAPastDayMisses() {
    assertFalse(PunchDayDeriver.isMissingCheckOut(1, DAY, DAY, LocalTime.MAX, null));
    assertTrue(PunchDayDeriver.isMissingCheckOut(1, DAY, DAY.plusDays(1), LocalTime.MIN, null));
    assertFalse(PunchDayDeriver.isMissingCheckOut(null, DAY, DAY.plusDays(1), LocalTime.MIN,
        null));
  }

  @Test
  void nullsAndPunchesWithoutATimeAreIgnored() {
    Punch noTime = new Punch();
    List<Punch> punches = new ArrayList<>();
    punches.add(null);
    punches.add(noTime);
    punches.add(at("a", DAY, "10:00"));

    PunchDayDeriver.Derived d = PunchDayDeriver.derive(punches, DAY, DAY, LocalTime.NOON, END);

    assertEquals(1, d.punchCount());
    assertEquals("10:00", d.inTime());
  }
}
