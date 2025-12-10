package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.Event;
import com.inventory.notifications.domain.model.EventType;
import com.inventory.notifications.domain.model.EventStatus;
import com.inventory.notifications.domain.model.ReminderStatus;
import com.inventory.notifications.domain.repository.EventRepository;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

  private final EventRepository eventRepository;
  private final ReminderRepository reminderRepository;
  private final ReminderMapper reminderMapper;

  // shopId -> list of active SSE connections
  private final Map<String, List<SseEmitter>> emittersByShop = new ConcurrentHashMap<>();

  private static final int MAX_RETRIES = 5;
  private static final long BASE_RETRY_SECONDS = 10L;

  public SseEmitter subscribe(String shopId) {
    SseEmitter emitter = new SseEmitter(0L); // no timeout

    emittersByShop
      .computeIfAbsent(shopId, k -> new CopyOnWriteArrayList<>())
      .add(emitter);

    emitter.onCompletion(() -> removeEmitter(shopId, emitter));
    emitter.onTimeout(() -> removeEmitter(shopId, emitter));
    emitter.onError(e -> removeEmitter(shopId, emitter));

    log.info("SSE subscribed for shopId={}, totalEmitters={}",
      shopId, emittersByShop.get(shopId).size());

    // (optional but nice) send a small "connected" event so client knows SSE is live
    try {
      emitter.send(
        SseEmitter.event()
          .name("INIT")
          .data("connected")
      );
    } catch (IOException e) {
      log.warn("Failed to send INIT SSE event for shopId={}: {}", shopId, e.getMessage());
    }

    // NEW: send any pending, undelivered events to this emitter
    sendPendingEventsToSingleEmitter(shopId, emitter);

    return emitter;
  }

  private void sendPendingEventsToSingleEmitter(String shopId, SseEmitter emitter) {
    List<Event> pendingEvents =
      eventRepository.findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(shopId);

    if (pendingEvents.isEmpty()) {
      return;
    }

    log.info("Replaying {} pending events for shopId={}", pendingEvents.size(), shopId);

    for (Event event : pendingEvents) {
      try {
        Reminder reminder = reminderRepository.findById(event.getReminderId())
          .orElse(null);

        if (reminder == null) {
          log.warn("Skipping pending event {} because reminder {} not found",
            event.getId(), event.getReminderId());
          continue;
        }

        ReminderResponse payload = reminderMapper.toResponse(reminder);

        emitter.send(
          SseEmitter.event()
            .name("REMINDER_DUE")
            .id(event.getId())
            .data(payload)
        );

        // Mark this event as delivered
        event.setDelivered(true);
        event.setDeliveredAt(Instant.now());
        eventRepository.save(event);

      } catch (IOException ex) {
        log.warn("Failed to send pending SSE event {} to shopId={}: {}",
          event.getId(), shopId, ex.getMessage());
        emitter.complete();
        removeEmitter(shopId, emitter);
        break; // stop processing more events for this (now dead) emitter
      } catch (Exception ex) {
        log.error("Unexpected error while replaying event {}: {}",
          event.getId(), ex.getMessage(), ex);
      }
    }
  }


  private void removeEmitter(String shopId, SseEmitter emitter) {
    List<SseEmitter> emitters = emittersByShop.get(shopId);
    if (emitters != null) {
      emitters.remove(emitter);
      if (emitters.isEmpty()) {
        emittersByShop.remove(shopId);
      }
    }
  }

  /**
   * Called when a reminder is due. Creates a event row and
   * tries to push it over SSE to all clients of that shop.
   */
  public void recordAndBroadcastReminderDue(Reminder reminder) {
    try {
      Event event = Event.builder()
        .reminderId(reminder.getId())
        .shopId(reminder.getShopId())
        .type(EventType.valueOf(reminder.getType().name()))
        .statusAtTrigger(EventStatus.valueOf(reminder.getStatus().name()))
        .triggeredAt(Instant.now())
        .notes(reminder.getNotes())
        .delivered(false)
        .retryCount(0)
        .build();

      event = eventRepository.save(event);

      broadcastEvent(event, reminder);

    } catch (Exception e) {
      log.error("Failed to create/broadcast event for reminderId={}: {}",
        reminder.getId(), e.getMessage(), e);
    }
  }

  /**
   * Try to send event to current emitters and update event fields accordingly.
   * Returns true if delivered to at least one emitter.
   */
  private boolean attemptBroadcastAndUpdate(Event event, Reminder reminder) {
    boolean delivered = broadcastEvent(event, reminder);

    if (delivered) {
      if (!event.isDelivered()) {
        event.setDelivered(true);
        event.setDeliveredAt(Instant.now());
        eventRepository.save(event);
      }
      return true;
    } else {
      // mark a failed attempt: increment retryCount, set lastAttemptAt and compute nextRetryAt with backoff
      try {
        int nextRetryCount = event.getRetryCount() + 1;
        event.setRetryCount(nextRetryCount);
        Instant now = Instant.now();
        event.setLastAttemptAt(now);

        // exponential backoff: BASE_RETRY_SECONDS * 2^(retryCount-1)
        long backoffSeconds = (long) (BASE_RETRY_SECONDS * Math.pow(2, Math.max(0, nextRetryCount - 1)));
        // cap backoff to something reasonable (e.g. 1 day)
        long maxBackoff = Duration.ofDays(1).getSeconds();
        if (backoffSeconds > maxBackoff) backoffSeconds = maxBackoff;

        event.setNextRetryAt(now.plusSeconds(backoffSeconds));
        eventRepository.save(event);
      } catch (Exception ex) {
        log.error("Failed to update retry metadata for event {}: {}", event.getId(), ex.getMessage(), ex);
      }
      return false;
    }
  }

  /**
   * Broadcasts an event to current emitters without touching DB fields (DB updates are handled by caller).
   * Returns true if delivered to at least one emitter.
   */
  private boolean broadcastEvent(Event event, Reminder reminder) {
    String shopId = event.getShopId();
    List<SseEmitter> emitters = emittersByShop.get(shopId);

    if (emitters == null || emitters.isEmpty()) {
      log.info("No active SSE emitters for shopId={} (event {})", shopId, event.getId());
      return false;
    }

    ReminderResponse payload = reminderMapper.toResponse(reminder);
    boolean deliveredToAtLeastOne = false;

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("REMINDER_DUE").id(event.getId()).data(payload));
        deliveredToAtLeastOne = true;
      } catch (IOException ex) {
        log.warn("Failed to send SSE to shopId={} emitter: {}", shopId, ex.getMessage());
        // emitter seems dead — clean up
        emitter.complete();
        removeEmitter(shopId, emitter);
      } catch (Exception ex) {
        log.error("Unexpected error sending SSE event {}: {}", event.getId(), ex.getMessage(), ex);
      }
    }

    return deliveredToAtLeastOne;
  }

  /**
   * Periodically scans for due reminders and dispatches them as events.
   * Runs every 30s by default (configurable via property).
   */
  @Scheduled(fixedDelayString = "${reminders.dispatch-interval-ms:30000}")
  public void dispatchDueReminders() {
    Instant now = Instant.now();
    List<Reminder> dueReminders;

    try {
      dueReminders = reminderRepository
        .findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(
          ReminderStatus.PENDING, now
        );
    } catch (Exception e) {
      log.error("Failed fetching due reminders at {}: {}", now, e.getMessage(), e);
      return;
    }

    if (dueReminders.isEmpty()) {
      return;
    }

    log.info("Found {} due reminders at {}", dueReminders.size(), now);

    for (Reminder reminder : dueReminders) {
      try {
        // mark as SENT so we don't send duplicates
        reminder.setStatus(ReminderStatus.SENT);
        reminder.setUpdatedAt(now);
        reminderRepository.save(reminder);

        // create event row + broadcast over SSE
        recordAndBroadcastReminderDue(reminder);
      } catch (Exception e) {
        log.error("Failed to dispatch reminderId={}: {}", reminder.getId(), e.getMessage(), e);
      }
    }
  }

  // ---------- Scheduled retry job ----------
  // Runs more frequently than dispatch or configurable separately.
  @Scheduled(fixedDelayString = "${reminders.retry-interval-ms:15000}")
  public void retryPendingEvents() {
    Instant now = Instant.now();
    List<Event> toRetry;
    try {
      toRetry = eventRepository.findByDeliveredFalseAndRetryCountLessThanAndNextRetryAtLessThanEqualOrderByTriggeredAtAsc(
        MAX_RETRIES, now);
    } catch (Exception e) {
      log.error("Failed fetching events for retry at {}: {}", now, e.getMessage(), e);
      return;
    }

    if (toRetry.isEmpty()) return;

    log.info("Retrying {} pending events", toRetry.size());

    for (Event event : toRetry) {
      try {
        Reminder reminder = reminderRepository.findById(event.getReminderId()).orElse(null);
        if (reminder == null) {
          log.warn("Skipping retry for event {} because reminder {} not found", event.getId(), event.getReminderId());
          // consider marking event as delivered=true or failed permanently if reminder is gone. For now, mark delivered to stop retrying:
          event.setDelivered(true);
          event.setDeliveredAt(Instant.now());
          eventRepository.save(event);
          continue;
        }

        boolean delivered = attemptBroadcastAndUpdate(event, reminder);

        if (delivered) {
          // Mark reminder SENT only when event delivered (business decision). If you prefer marking reminders as SENT separately,
          // implement that logic here (update reminder.status to SENT).
          try {
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setUpdatedAt(now);
            reminderRepository.save(reminder);
          } catch (Exception ex) {
            log.warn("Failed to mark reminder {} SENT after delivery: {}", reminder.getId(), ex.getMessage());
          }
        } else {
          if (event.getRetryCount() >= MAX_RETRIES) {
            log.warn("Event {} exhausted retries ({}). Marking as undelivered for manual inspection.", event.getId(), event.getRetryCount());
            // optional: mark delivered=false but set nextRetryAt far in future or set a flag for manual handling
            // e.g. event.setNextRetryAt(now.plus(Duration.ofDays(30)));
            eventRepository.save(event);
          }
        }
      } catch (Exception e) {
        log.error("Failed retrying event {}: {}", event.getId(), e.getMessage(), e);
      }
    }
  }
}
