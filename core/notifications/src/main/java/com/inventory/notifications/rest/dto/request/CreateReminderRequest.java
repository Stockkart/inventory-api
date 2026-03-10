package com.inventory.notifications.rest.dto.request;

import com.inventory.notifications.domain.model.ReminderType;
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
