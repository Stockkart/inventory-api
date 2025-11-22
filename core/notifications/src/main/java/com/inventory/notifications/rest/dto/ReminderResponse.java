package com.inventory.notifications.rest.dto;

import lombok.Builder;
import lombok.Data;
import lombok.Value;

import java.time.Instant;

@Data
public class ReminderResponse {
    String reminderId;
    String inventoryId;
    Instant reminderAt;
    Instant expiryDate;
    Instant snoozeUntil;
    String status;
}

