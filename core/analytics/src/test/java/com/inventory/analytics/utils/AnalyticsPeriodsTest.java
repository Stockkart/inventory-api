package com.inventory.analytics.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Chart buckets: India time, keys in the old shapes, months end at the next month's start. */
class AnalyticsPeriodsTest {

  @Test
  void keysFromMongoDayAndHourBuckets() {
    assertEquals("2026-10-01T05:00", AnalyticsPeriods.keyOf("2026-10-01T05:00", "hour"));
    assertEquals("2026-10-01", AnalyticsPeriods.keyOf("2026-10-01", "day"));
    assertEquals("2026-09-28_week", AnalyticsPeriods.keyOf("2026-10-01", "week")); // Thursday → Monday
    assertEquals("2026-09-28_week", AnalyticsPeriods.keyOf("2026-10-04", "WEEK")); // Sunday stays in its week
    assertEquals("2026-10", AnalyticsPeriods.keyOf("2026-10-31", "month"));
    assertEquals("2026-10-31", AnalyticsPeriods.keyOf("2026-10-31", "quarter")); // unknown → day, as before
    assertTrue(AnalyticsPeriods.hourly("Hour"));
    assertFalse(AnalyticsPeriods.hourly("day"));
    assertFalse(AnalyticsPeriods.hourly(null));
  }

  @Test
  void bucketsStartAtIndiaMidnight() {
    assertEquals(Instant.parse("2026-09-30T18:30:00Z"), AnalyticsPeriods.start("2026-10-01", "day"));
    assertEquals(Instant.parse("2026-09-30T23:30:00Z"), AnalyticsPeriods.start("2026-10-01T05:00", "hour"));
    assertEquals(Instant.parse("2026-09-27T18:30:00Z"), AnalyticsPeriods.start("2026-09-28_week", "week"));
    assertEquals(Instant.parse("2026-09-30T18:30:00Z"), AnalyticsPeriods.start("2026-10", "month"));
  }

  @Test
  void monthsEndAtTheNextMonthNotAfterThirtyDays() {
    assertEquals(Instant.parse("2026-01-31T18:30:00Z"), AnalyticsPeriods.end(AnalyticsPeriods.start("2026-01", "month"), "month"));
    assertEquals(Instant.parse("2026-02-28T18:30:00Z"), AnalyticsPeriods.end(AnalyticsPeriods.start("2026-02", "month"), "month"));
    assertEquals(Instant.parse("2028-02-29T18:30:00Z"), AnalyticsPeriods.end(AnalyticsPeriods.start("2028-02", "month"), "month"));
    Instant day = AnalyticsPeriods.start("2026-10-01", "day");
    assertEquals(day.plusSeconds(86_400), AnalyticsPeriods.end(day, "day"));
    assertEquals(day.plusSeconds(7 * 86_400), AnalyticsPeriods.end(day, "week"));
    assertEquals(day.plusSeconds(3_600), AnalyticsPeriods.end(day, "hour"));
  }
}
