package com.inventory.notifications.rest.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class SnoozeReminderRequest {
  private Instant snoozeUntil;
}

