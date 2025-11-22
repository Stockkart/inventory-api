package com.inventory.notifications.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reminders")
public class Reminder {

    @Id
    private String id;
    private String inventoryId;
    private String shopId;
    private Instant reminderAt;
    private Instant expiryDate;
    private Instant snoozeUntil;
    private String status;
}

