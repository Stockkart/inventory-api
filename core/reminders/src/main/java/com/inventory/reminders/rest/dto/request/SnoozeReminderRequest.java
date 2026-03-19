package com.inventory.reminders.rest.dto.request;

import lombok.Data;

@Data
public class SnoozeReminderRequest {
  private Integer snoozeDays;
}
