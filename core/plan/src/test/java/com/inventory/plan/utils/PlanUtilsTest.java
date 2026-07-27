package com.inventory.plan.utils;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlanUtilsTest {

  @Test
  void wholeDaysUntilReturnsNullWithoutAnExpiry() {
    assertNull(PlanUtils.wholeDaysUntil(null));
  }

  @Test
  void wholeDaysUntilCountsFullDaysOnly() {
    // 3 days minus a minute is still only 2 whole days remaining.
    Instant expiry = Instant.now().plus(3, ChronoUnit.DAYS).minus(1, ChronoUnit.MINUTES);
    assertEquals(2, PlanUtils.wholeDaysUntil(expiry));
  }

  @Test
  void wholeDaysUntilTruncatesRatherThanRounds() {
    // 18 hours left must read as 0 days, not overstate as 1.
    Instant expiry = Instant.now().plus(18, ChronoUnit.HOURS);
    assertEquals(0, PlanUtils.wholeDaysUntil(expiry));
  }

  @Test
  void wholeDaysUntilFloorsAtZeroOnceExpired() {
    Instant expiry = Instant.now().minus(5, ChronoUnit.DAYS);
    assertEquals(0, PlanUtils.wholeDaysUntil(expiry));
  }

  @Test
  void isExpiredTracksThePast() {
    assertEquals(true, PlanUtils.isExpired(Instant.now().minus(1, ChronoUnit.SECONDS)));
    assertEquals(false, PlanUtils.isExpired(Instant.now().plus(1, ChronoUnit.DAYS)));
    assertEquals(false, PlanUtils.isExpired(null));
  }
}
