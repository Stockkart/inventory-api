package com.inventory.notifications.utils;

import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.notifications.rest.dto.CustomReminderRequest;
import com.inventory.notifications.utils.constants.ReminderConstants;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;

/**
 * Utility methods for reminder-related computations.
 */
@Slf4j
public final class ReminderUtils {

  private ReminderUtils() {}

  /**
   * Computes reminderAt from explicit value or base date (default: days before base date).
   * Returns null if result is in the past or cannot be computed.
   */
  public static Instant computeReminderTime(
      Instant explicitReminderAt,
      Instant baseDate,
      long daysBefore,
      String contextMessage) {
    Instant now = Instant.now();

    if (baseDate == null && explicitReminderAt == null) {
      log.debug("No baseDate or explicitReminderAt for {}. skipping", contextMessage);
      return null;
    }

    Instant result = explicitReminderAt;
    if (result == null && baseDate != null) {
      result = baseDate.minus(Duration.ofDays(daysBefore));
    }

    if (result != null && result.isAfter(now)) {
      return result;
    }

    log.warn("Computed reminderAt {} is null or in the past for {}, skipping", result, contextMessage);
    return null;
  }

  /**
   * Computes expiry reminder time for inventory create request.
   */
  public static Instant computeExpiryReminderTime(CreateReminderForInventoryRequest request) {
    if (request == null) {
      return null;
    }
    return computeReminderTime(
        request.getReminderAt(),
        request.getExpiryDate(),
        ReminderConstants.REMINDER_DAYS_BEFORE,
        String.format("expiry reminder on inventoryId=%s", request.getInventoryId()));
  }

  /**
   * Computes custom reminder time from custom reminder request.
   */
  public static Instant computeCustomReminderTime(CustomReminderRequest customReminder) {
    if (customReminder == null) {
      return null;
    }
    return computeReminderTime(
        customReminder.getReminderAt(),
        customReminder.getEndDate(),
        ReminderConstants.REMINDER_DAYS_BEFORE,
        "custom reminder");
  }
}
