package com.inventory.hr.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.hr.domain.model.HrSettings;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PayCycleTest {

  private static void assertRange(String from, String to, PayCycle.Range r) {
    assertEquals(LocalDate.parse(from), r.from());
    assertEquals(LocalDate.parse(to), r.to());
  }

  @Test
  void startDayOneIsTheCalendarMonth() {
    assertRange("2026-10-01", "2026-10-31", PayCycle.range("2026-10", 1));
    assertRange("2024-02-01", "2024-02-29", PayCycle.range("2024-02", 1));
    assertRange("2025-02-01", "2025-02-28", PayCycle.range("2025-02", 1));
    assertEquals(31, PayCycle.range("2026-10", 1).days());
    assertEquals("2026-10", PayCycle.periodOf("2026-10-01", 1));
    assertEquals("2026-10", PayCycle.periodOf("2026-10-31", 1));
  }

  @Test
  void startDay26IsNamedByTheMonthItEndsIn() {
    PayCycle.Range oct = PayCycle.range("2026-10", 26);
    assertRange("2026-09-26", "2026-10-25", oct);
    assertEquals(30, oct.days());
    assertEquals("2026-10", PayCycle.periodOf("2026-09-26", 26));
    assertEquals("2026-10", PayCycle.periodOf("2026-10-25", 26));
    assertEquals("2026-11", PayCycle.periodOf("2026-10-26", 26));
    assertEquals("2026-09", PayCycle.periodOf("2026-09-25", 26));
  }

  @Test
  void januaryAndMarchCrossTheYearAndFebruary() {
    // January's cycle starts in December of the year before
    assertRange("2025-12-26", "2026-01-25", PayCycle.range("2026-01", 26));
    assertEquals("2026-01", PayCycle.periodOf("2025-12-31", 26));
    assertEquals("2026-01", PayCycle.periodOf("2025-12-26", 26));
    // a December date on or after the start day belongs to next year's January
    assertEquals("2025-12", PayCycle.periodOf("2025-12-25", 26));
    // March's cycle covers the end of February: leap and common years
    assertRange("2024-02-26", "2024-03-25", PayCycle.range("2024-03", 26));
    assertEquals(29, PayCycle.range("2024-03", 26).days());
    assertRange("2025-02-26", "2025-03-25", PayCycle.range("2025-03", 26));
    assertEquals(28, PayCycle.range("2025-03", 26).days());
    assertEquals("2024-03", PayCycle.periodOf("2024-02-29", 26));
  }

  @Test
  void startDay28() {
    assertRange("2024-01-28", "2024-02-27", PayCycle.range("2024-02", 28));
    assertRange("2024-02-28", "2024-03-27", PayCycle.range("2024-03", 28));
    assertEquals(29, PayCycle.range("2024-03", 28).days());
    assertEquals(28, PayCycle.range("2025-03", 28).days());
    assertEquals("2024-03", PayCycle.periodOf("2024-02-29", 28));
    assertEquals("2024-03", PayCycle.periodOf("2024-02-28", 28));
    assertEquals("2024-02", PayCycle.periodOf("2024-02-27", 28));
  }

  @Test
  void everyDateFallsInExactlyTheCycleItMapsTo() {
    for (int start : List.of(1, 2, 15, 26, 28)) {
      for (LocalDate d = LocalDate.of(2023, 12, 1); d.isBefore(LocalDate.of(2025, 3, 1));
          d = d.plusDays(1)) {
        YearMonth period = PayCycle.periodOf(d, start);
        PayCycle.Range r = PayCycle.range(period, start);
        assertEquals(true, r.contains(d), d + " start " + start);
        // consecutive cycles touch: next cycle starts the day after this one ends
        assertEquals(r.to().plusDays(1), PayCycle.range(period.plusMonths(1), start).from());
      }
    }
  }

  @Test
  void startDayDefaultsToOne() {
    assertEquals(1, PayCycle.startDay((Integer) null));
    assertEquals(1, PayCycle.startDay(0));
    assertEquals(1, PayCycle.startDay(29));
    assertEquals(1, PayCycle.startDay((HrSettings) null));
    HrSettings s = new HrSettings();
    s.setPayCycleStartDay(26);
    assertEquals(26, PayCycle.startDay(s));
  }

  @Test
  void periodsBetweenAndOfDates() {
    assertEquals(Set.of("2026-10", "2026-11"), PayCycle.periodsBetween(
        LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-27"), 26));
    assertEquals(Set.of("2026-10"), PayCycle.periodsBetween(
        LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-27"), 1));
    assertEquals(Set.of("2026-10", "2026-11"),
        PayCycle.periodsOf(List.of("2026-09-27", "2026-10-31", "2026-10-01"), 26));
  }

  @Test
  void labelsAndStoredRanges() {
    assertEquals("26 Sep – 25 Oct 2026", PayCycle.range("2026-10", 26).label());
    assertEquals("26 Dec 2025 – 25 Jan 2026", PayCycle.range("2026-01", 26).label());
    assertEquals("1 Oct – 31 Oct 2026", PayCycle.range("2026-10", 1).label());
    // documents from before pay cycles: the calendar month
    assertRange("2025-04-01", "2025-04-30", PayCycle.stored("2025-04", null, null));
    assertRange("2026-09-26", "2026-10-25",
        PayCycle.stored("2026-10", "2026-09-26", "2026-10-25"));
  }
}
