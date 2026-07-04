package com.inventory.reminders.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.inventory.reminders.rest.dto.request.CustomReminderRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReminderUtilsCustomReminderTest {

  @Test
  void explicitReminderAt_isHonoredEvenWhenInPast() {
    Instant past = Instant.parse("2020-06-28T16:40:00Z");
    CustomReminderRequest reminder = new CustomReminderRequest();
    reminder.setReminderAt(past);
    reminder.setEndDate(Instant.parse("2030-06-28T17:40:00Z"));

    assertEquals(past, ReminderUtils.computeCustomReminderTime(reminder));
  }

  @Test
  void endDateOnly_usesDaysBeforeAndSkipsWhenComputedTimeIsPast() {
    CustomReminderRequest reminder = new CustomReminderRequest();
    reminder.setEndDate(Instant.parse("2020-01-15T00:00:00Z"));

    assertNull(ReminderUtils.computeCustomReminderTime(reminder));
  }

  @Test
  void endDateOnly_computesFutureReminderFromEndDate() {
    CustomReminderRequest reminder = new CustomReminderRequest();
    reminder.setEndDate(Instant.now().plusSeconds(86400L * 60));

    assertNotNull(ReminderUtils.computeCustomReminderTime(reminder));
  }
}
