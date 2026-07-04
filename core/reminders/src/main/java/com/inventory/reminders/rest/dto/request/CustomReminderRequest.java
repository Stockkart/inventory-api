package com.inventory.reminders.rest.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.inventory.common.jackson.FlexibleInstantDeserializer;
import lombok.Data;

import java.time.Instant;

@Data
public class CustomReminderRequest {
  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant reminderAt;

  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant endDate;

  private String notes;
}
