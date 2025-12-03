package com.inventory.notifications.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reminders")
public class Reminder {

  @Id
  private String id;
  private String inventoryId;
  private Instant reminderAt;
  private Instant endDate;
  private String notes;
  @Builder.Default
  private Integer snoozeDays = 0;

  private ReminderStatus status;
  private Instant createdAt;
  private Instant updatedAt;
}

