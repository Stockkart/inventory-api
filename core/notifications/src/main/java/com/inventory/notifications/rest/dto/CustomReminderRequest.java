package com.inventory.notifications.rest.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class CustomReminderRequest {
  private Instant reminderAt;
  private Instant endDate;
  private String notes;
}
