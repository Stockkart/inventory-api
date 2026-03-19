package com.inventory.reminders.rest.dto.response;

import com.inventory.reminders.domain.model.ReminderType;
import lombok.Data;

import java.time.Instant;

@Data
public class ReminderResponse {
  String reminderId;
  String inventoryId;
  Instant reminderAt;
  Instant expiryDate;
  Integer snoozeDays;
  String notes;
  String status;
  ReminderType type;
}
