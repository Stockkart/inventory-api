package com.inventory.reminders.rest.dto.request;

import com.inventory.reminders.domain.model.ReminderType;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateReminderRequest {
  private String shopId;
  private String inventoryId;
  private Instant reminderAt;
  private Instant endDate;
  private String notes;
  private ReminderType type;
}
