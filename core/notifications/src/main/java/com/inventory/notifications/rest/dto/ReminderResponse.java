package com.inventory.notifications.rest.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class ReminderResponse {
  String reminderId;
  String inventoryId;
  Instant reminderAt;
  Instant expiryDate;
  Instant snoozeUntil;
  String status;
}

