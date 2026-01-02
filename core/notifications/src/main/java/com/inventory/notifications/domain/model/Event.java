package com.inventory.notifications.domain.model;

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
@Document("events")
public class Event {

  @Id
  private String id;
  private String reminderId;
  private Object payloadJson;
  private String shopId;
  private EventType type;
  private EventStatus statusAtTrigger;
  private Instant triggeredAt;
  private String notes;         // optional, from reminder
  private boolean delivered;    // true when SSE delivered successfully
  private Instant deliveredAt;
  @Builder.Default
  private int retryCount = 0;
  private Instant lastAttemptAt;
  private Instant nextRetryAt;
}
