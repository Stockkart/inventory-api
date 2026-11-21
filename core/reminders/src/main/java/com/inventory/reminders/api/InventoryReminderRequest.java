package com.inventory.reminders.api;

import java.time.Instant;
import java.util.List;

/**
 * Reminders to create for a newly added stock lot.
 *
 * @param expiryDate lot expiry; when set, an expiry reminder is created
 * @param reminderAt explicit time for the expiry reminder; null means the default lead time
 * @param customReminders extra reminders; null or empty for none
 */
public record InventoryReminderRequest(
    String shopId,
    String inventoryId,
    Instant expiryDate,
    Instant reminderAt,
    List<CustomReminder> customReminders) {}
