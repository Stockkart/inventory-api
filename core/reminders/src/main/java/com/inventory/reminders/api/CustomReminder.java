package com.inventory.reminders.api;

import java.time.Instant;

/**
 * A reminder the user asked for when adding stock.
 *
 * @param reminderAt when to remind; null with an {@code endDate} means a default lead time
 * @param endDate date the reminder is about (for example expiry)
 */
public record CustomReminder(Instant reminderAt, Instant endDate, String notes) {}
