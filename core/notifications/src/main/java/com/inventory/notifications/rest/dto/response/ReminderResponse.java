package com.inventory.notifications.rest.dto.response;

import com.inventory.notifications.domain.model.ReminderType;
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
