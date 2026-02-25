package com.inventory.reminders.rest.dto;

import lombok.Data;

@Data
public class SnoozeReminderRequest {
  private Integer snoozeDays;
}

