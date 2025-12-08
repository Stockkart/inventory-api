package com.inventory.notifications.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateReminderForInventoryRequest {
  private String shopId;
  private String inventoryId;
  private Instant expiryDate;
  private Instant reminderAt;
  private Instant newReminderAt;
  private Instant reminderEndDate;
  private String reminderNotes;
}

