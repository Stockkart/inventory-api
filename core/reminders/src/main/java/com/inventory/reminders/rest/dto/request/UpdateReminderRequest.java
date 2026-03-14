package com.inventory.reminders.rest.dto.request;

import lombok.Data;

import java.time.Instant;

@Data
public class UpdateReminderRequest {

  private Instant reminderAt;
  private Instant endDate;
  private String notes;
  private String status;
}
