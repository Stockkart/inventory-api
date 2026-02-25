package com.inventory.reminders.rest.dto;

import com.inventory.reminders.domain.model.ReminderStatus;
import com.inventory.reminders.domain.model.ReminderType;
import lombok.Data;

import java.time.Instant;

@Data
public class ReminderDetailListResponse {
  private String id;
  private Instant reminderAt;
  private Instant endDate;
  private String notes;
  private ReminderStatus status;
  private ReminderType type;
  private ReminderInventorySummary inventory;
}

