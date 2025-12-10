package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderEvent;
import com.inventory.notifications.domain.model.ReminderStatus;
import com.inventory.notifications.domain.repository.ReminderEventRepository;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderEventService {

  private final ReminderEventRepository reminderEventRepository;
  private final ReminderRepository reminderRepository;
  private final ReminderMapper reminderMapper;

  // shopId -> list of active SSE connections
  private final Map<String, List<SseEmitter>> emittersByShop = new ConcurrentHashMap<>();

  // ===================== SSE SUBSCRIPTION =====================
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

    // 🔹 (optional but nice) send a small "connected" event so client knows SSE is live
    try {
      emitter.send(
        SseEmitter.event()
          .name("INIT")
          .data("connected")
      );
    } catch (IOException e) {
      log.warn("Failed to send INIT SSE event for shopId={}: {}", shopId, e.getMessage());
    }

    // 🔹 NEW: send any pending, undelivered events to this emitter
    sendPendingEventsToSingleEmitter(shopId, emitter);

    return emitter;
  }

  private void sendPendingEventsToSingleEmitter(String shopId, SseEmitter emitter) {
    List<ReminderEvent> pendingEvents =
      reminderEventRepository.findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(shopId);

    if (pendingEvents.isEmpty()) {
      return;
    }

    log.info("Replaying {} pending events for shopId={}", pendingEvents.size(), shopId);

    for (ReminderEvent event : pendingEvents) {
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
        reminderEventRepository.save(event);

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

  // ===================== EVENT CREATION + BROADCAST =====================
  /**
   * Called when a reminder is due. Creates a ReminderEvent row and
   * tries to push it over SSE to all clients of that shop.
   */
  public void recordAndBroadcastReminderDue(Reminder reminder) {
    try {
      ReminderEvent event = ReminderEvent.builder()
        .reminderId(reminder.getId())
        .shopId(reminder.getShopId())
        .type(reminder.getType())
        .statusAtTrigger(reminder.getStatus())
        .triggeredAt(Instant.now())
        .notes(reminder.getNotes())
        .delivered(false)
        .retryCount(0)
        .build();

      event = reminderEventRepository.save(event);

      broadcastEvent(event, reminder);

    } catch (Exception e) {
      log.error("Failed to create/broadcast event for reminderId={}: {}",
        reminder.getId(), e.getMessage(), e);
    }
  }

  private void broadcastEvent(ReminderEvent event, Reminder reminder) {
    String shopId = event.getShopId();
    List<SseEmitter> emitters = emittersByShop.get(shopId);

    if (emitters == null || emitters.isEmpty()) {
      log.info("No active SSE emitters for shopId={}, leaving event undelivered", shopId);
      return;
    }

    ReminderResponse payload = reminderMapper.toResponse(reminder);
    boolean deliveredToAtLeastOne = false;

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(
          SseEmitter.event()
            .name("REMINDER_DUE")
            .id(event.getId())
            .data(payload)
        );
        deliveredToAtLeastOne = true;
      } catch (IOException ex) {
        log.warn("Failed to send SSE to shopId={} emitter: {}", shopId, ex.getMessage());
        emitter.complete();
        removeEmitter(shopId, emitter);
      }
    }

    if (deliveredToAtLeastOne && !event.isDelivered()) {
      event.setDelivered(true);
      event.setDeliveredAt(Instant.now());
      reminderEventRepository.save(event);
    }
  }

  // ===================== SCHEDULER INSIDE THIS SERVICE =====================
  /**
   * Periodically scans for due reminders and dispatches them as events.
   * Runs every 30s by default (configurable via property).
   */
  @Scheduled(fixedDelayString = "${reminders.dispatch-interval-ms:30000}")
  public void dispatchDueReminders() {
    Instant now = Instant.now();

    List<Reminder> dueReminders =
      reminderRepository.findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(
        ReminderStatus.PENDING,
        now
      );

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
}
