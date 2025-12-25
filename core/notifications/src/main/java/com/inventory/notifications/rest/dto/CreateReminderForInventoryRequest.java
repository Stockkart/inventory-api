package com.inventory.notifications.rest.dto;

import com.inventory.notifications.rest.dto.CustomReminderRequest;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReminderForInventoryRequest {
  private String shopId;
  private String inventoryId;
  private Instant expiryDate;
  private Instant reminderAt;
  private List<CustomReminderRequest> customReminders;
}

