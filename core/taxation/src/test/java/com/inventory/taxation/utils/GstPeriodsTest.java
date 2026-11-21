package com.inventory.taxation.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GST periods and dates are Indian ones even on a server that runs on UTC. */
class GstPeriodsTest {

  private TimeZone serverZone;

  @BeforeEach
  void runOnUtc() {
    serverZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  }

  @AfterEach
  void restore() {
    TimeZone.setDefault(serverZone);
  }

  @Test
  void octoberStartsAtMidnightInIndia() {
    assertEquals(Instant.parse("2026-09-30T18:30:00Z"), GstPeriods.monthStart(2026, 10));
    assertEquals(Instant.parse("2026-10-31T18:30:00Z"), GstPeriods.monthEnd(2026, 10));
  }

  @Test
  void aBillAtHalfPastMidnightOnTheFirstIsDatedTheFirst() {
    assertEquals(LocalDate.of(2026, 10, 1), GstPeriods.dateOf(Instant.parse("2026-09-30T19:00:00Z")));
  }

  @Test
  void aBillLateOnTheLastDayStaysOnTheLastDay() {
    assertEquals(LocalDate.of(2026, 9, 30), GstPeriods.dateOf(Instant.parse("2026-09-30T18:29:59Z")));
  }
}
