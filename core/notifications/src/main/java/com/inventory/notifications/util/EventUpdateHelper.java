package com.inventory.notifications.util;

import com.inventory.notifications.domain.model.Event;
import com.inventory.notifications.domain.repository.EventRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
public class EventUpdateHelper {

  public static void markDelivered(Event event, EventRepository repo) {
    try {
      event.setDelivered(true);
      event.setDeliveredAt(Instant.now());
      repo.save(event);
    } catch (Exception e) {
      log.error("Failed to mark event {} delivered: {}", event.getId(), e.getMessage());
    }
  }

  public static void markRetry(Event event, EventRepository repo, long nextRetrySeconds) {
    try {
      event.setRetryCount(event.getRetryCount() + 1);
      event.setLastAttemptAt(Instant.now());
      event.setNextRetryAt(Instant.now().plusSeconds(nextRetrySeconds));
      repo.save(event);
    } catch (Exception e) {
      log.error("Failed to update retry metadata for event {}: {}", event.getId(), e.getMessage());
    }
  }
}
