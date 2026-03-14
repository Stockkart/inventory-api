package com.inventory.reminders.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document("reminders")
public class Reminder {

  @Id
  private String id;
  private String shopId;
  private String inventoryId;
  private Instant reminderAt;
  private Instant endDate;
  private String notes;
  @Builder.Default
  private Integer snoozeDays = 0;
  private ReminderStatus status;
  private ReminderType type;
  private Instant createdAt;
  private Instant updatedAt;
}
