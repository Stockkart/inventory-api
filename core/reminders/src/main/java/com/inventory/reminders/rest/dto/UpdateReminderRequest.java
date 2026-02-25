package com.inventory.reminders.rest.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class UpdateReminderRequest {

  private Instant reminderAt; // optional
  private Instant endDate;    // optional
  private String notes;       // optional
  private String status;      // optional, e.g. PENDING, DONE, etc.
}
