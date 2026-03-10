package com.inventory.notifications.rest.dto.request;

import lombok.Data;

@Data
public class SnoozeReminderRequest {
  private Integer snoozeDays;
}
