package com.inventory.product.rest.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.inventory.common.jackson.FlexibleInstantDeserializer;
import java.time.Instant;
import lombok.Data;

/**
 * A custom reminder in a stock-in request body. Same JSON as the reminders module's request:
 * {@code reminderAt} and {@code endDate} accept ISO-8601 or a plain date ({@code 2027-07-01}).
 */
@Data
public class CustomReminderRequest {

  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant reminderAt;

  @JsonDeserialize(using = FlexibleInstantDeserializer.class)
  private Instant endDate;

  private String notes;
}
