package com.inventory.reminders.service;

import com.inventory.reminders.domain.model.Event;
import com.inventory.reminders.domain.model.Reminder;
import com.inventory.reminders.domain.model.ReminderStatus;
import com.inventory.reminders.domain.repository.EventRepository;
import com.inventory.reminders.domain.repository.ReminderRepository;
import com.inventory.reminders.rest.dto.response.InventoryLowEventDto;
import com.inventory.reminders.rest.dto.response.ReminderDetailListResponse;
import com.inventory.reminders.mapper.EventMapper;
import com.inventory.reminders.mapper.ReminderMapper;
import com.inventory.reminders.utils.EventUtils;
import com.inventory.reminders.utils.SseEmitterUtils;
import com.inventory.reminders.utils.constants.EventConstants;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class EventService {

  @Autowired
  private EventRepository eventRepository;

  @Autowired
  private ReminderRepository reminderRepository;

  @Autowired
  private ReminderMapper reminderMapper;

  @Autowired
  private EventMapper eventMapper;

  // support multiple tabs/devices per shop
  private final Map<String, List<SseEmitter>> emittersByShop = new ConcurrentHashMap<>();

  // ---------------------------
  // Subscription / replay
  // ---------------------------
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

    // send INIT to client (non-fatal)
    try {
      emitter.send(SseEmitter.event().name("INIT").data("connected"));
    } catch (IOException e) {
      log.warn("Failed to send INIT SSE event for shopId={}: {}", shopId, e.getMessage());
    }

    // replay any pending undelivered events for this shop
    sendPendingEventsToSingleEmitter(shopId, emitter);

    return emitter;
  }

  private void sendPendingEventsToSingleEmitter(String shopId, SseEmitter emitter) {
    List<Event> pending;
    try {
      pending = eventRepository.findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(shopId);
    } catch (Exception e) {
      log.error("Failed loading pending events for shop {}: {}", shopId, e.getMessage(), e);
      pending = Collections.emptyList();
    }

    if (pending == null || pending.isEmpty()) return;

    log.info("Replaying {} pending events for shopId={}", pending.size(), shopId);

    for (Event event : pending) {
      try {
        if (event.getReminderId() == null || event.getReminderId().isBlank()) {
          log.debug("Skipping pending event {} (no reminderId, e.g. INVENTORY_LOW)", event.getId());
          continue;
        }
        Optional<Reminder> reminderOpt;
        try {
          reminderOpt = reminderRepository.findById(event.getReminderId());
        } catch (Exception dbEx) {
          log.error("Failed loading reminder {}: {}", event.getReminderId(), dbEx.getMessage(), dbEx);
          // DB error — skip this event for now (do not mark delivered)
          continue;
        }

        if (!reminderOpt.isPresent()) {
          log.warn("Skipping pending event {} because reminder {} not found", event.getId(), event.getReminderId());
          // mark event delivered to stop infinite retry for missing reminder
          try {
            event.setDelivered(true);
            event.setDeliveredAt(Instant.now());
            eventRepository.save(event);
          } catch (Exception saveEx) {
            log.error("Failed marking event {} delivered while skipping missing reminder: {}", event.getId(), saveEx.getMessage(), saveEx);
          }
          continue;
        }

        Reminder reminder = reminderOpt.get();
        ReminderDetailListResponse payload = reminderMapper.toDetailResponse(reminder);
        emitter.send(SseEmitter.event().name("REMINDER_DUE").id(event.getId()).data(payload));

        // mark event delivered
        try {
          event.setDelivered(true);
          event.setDeliveredAt(Instant.now());
          eventRepository.save(event);
        } catch (Exception saveEx) {
          log.error("Failed marking pending event {} delivered after replay: {}", event.getId(), saveEx.getMessage(), saveEx);
        }

      } catch (IOException ex) {
        log.warn("Failed sending pending SSE event {} to shopId={}: {}", event.getId(), shopId, ex.getMessage());
        emitter.complete();
        removeEmitter(shopId, emitter);
        break; // emitter dead; stop replay for it
      } catch (Exception ex) {
        log.error("Unexpected error while replaying event {}: {}", event.getId(), ex.getMessage(), ex);
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
   * Create an event record for a triggered reminder and try delivering it.
   * This variant MARKS THE REMINDER AS SENT FIRST, then persists an Event record,
   * and then attempts immediate delivery. If reminder update fails we abort.
   *
   * NOTE: marking the reminder before persisting the event means there is a
   * chance the reminder is marked SENT while event persistence fails — we log that case.
   * If you want strict atomicity consider MongoDB transactions (requires replica-set).
   */
  public void recordAndBroadcastReminderDue(Reminder reminder) {
    if (reminder == null || reminder.getId() == null) {
      log.warn("recordAndBroadcastReminderDue called with null reminder or id");
      return;
    }

    Instant now = Instant.now();

    // 1) Mark reminder as SENT first (defensive: log & abort if it fails)
    boolean reminderUpdated = false;
    try {
      reminder.setStatus(ReminderStatus.SENT);
      reminder.setUpdatedAt(now);
      reminderRepository.save(reminder);
      reminderUpdated = true;
    } catch (Exception e) {
      log.error("Failed marking reminder {} SENT before event creation: {}", reminder.getId(), e.getMessage(), e);
    }

    if (!reminderUpdated) {
      log.error("Aborting event creation for reminder {} because reminder update failed", reminder.getId());
      return;
    }

    // 2) Build and persist event via mapper
    Event event = eventMapper.toEventFromReminderDue(reminder, now);

    Event saved = null;
    try {
      saved = eventRepository.save(event);
    } catch (Exception e) {
      log.error("Failed saving event for reminder {}: {}", reminder.getId(), e.getMessage(), e);
    }

    if (saved == null) {
      // couldn't persist event — reminder already marked SENT; log and return
      log.error("Event persist failed for reminder {} after marking reminder SENT. Manual reconciliation may be required.", reminder.getId());
      return;
    }

    // 3) attempt to broadcast and update event metadata (retry count / delivered)
    boolean delivered = attemptBroadcastAndUpdate(saved, reminder);

    if (delivered) {
      log.info("Event {} delivered immediately for reminder {}", saved.getId(), reminder.getId());
    } else {
      log.info("Event {} for reminder {} not delivered immediately, will be retried", saved.getId(), reminder.getId());
    }
  }

  /**
   * Try to broadcast (without DB changes) then update event metadata.
   * Returns true if delivered to at least one emitter.
   */
  private boolean attemptBroadcastAndUpdate(Event event, Reminder reminder) {
    boolean delivered = broadcastEvent(event, reminder);

    if (delivered) {
      if (!event.isDelivered()) {
        try {
          event.setDelivered(true);
          event.setDeliveredAt(Instant.now());
          eventRepository.save(event);
        } catch (Exception e) {
          log.error("Failed to mark event {} delivered after successful broadcast: {}", event.getId(), e.getMessage(), e);
        }
      }
      return true;
    }

    // failed delivery -> increment retry metadata with exponential backoff
    try {
      int nextRetryCount = event.getRetryCount() + 1;
      long backoffSeconds = EventUtils.computeBackoffSeconds(nextRetryCount);

      event.setRetryCount(nextRetryCount);
      event.setLastAttemptAt(Instant.now());
      event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));

      try {
        eventRepository.save(event);
      } catch (Exception saveEx) {
        log.error("Failed updating retry metadata for event {}: {}", event.getId(), saveEx.getMessage(), saveEx);
      }
    } catch (Exception ex) {
      log.error("Failed to compute/update retry metadata for event {}: {}", event.getId(), ex.getMessage(), ex);
    }

    return false;
  }

  /**
   * Broadcasts an event to all active emitters for the event's shop.
   * Returns true if at least one emitter successfully received it.
   */
  private boolean broadcastEvent(Event event, Reminder reminder) {
    String shopId = event.getShopId();
    List<SseEmitter> emitters = emittersByShop.get(shopId);

    if (emitters == null || emitters.isEmpty()) {
      log.info("No active SSE emitters for shopId={} (event {})", shopId, event.getId());
      return false;
    }

    ReminderDetailListResponse payload = reminderMapper.toDetailResponse(reminder);
    boolean deliveredToAtLeastOne = false;

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("REMINDER_DUE").id(event.getId()).data(payload));
        deliveredToAtLeastOne = true;
      } catch (IOException ex) {
        log.warn("Failed to send SSE to shopId={} emitter: {}", shopId, ex.getMessage());
        SseEmitterUtils.safeComplete(emitter);
        removeEmitter(shopId, emitter);
      } catch (Exception ex) {
        log.error("Unexpected error sending SSE event {}: {}", event.getId(), ex.getMessage(), ex);
      }
    }

    return deliveredToAtLeastOne;
  }

  /**
   * Single scheduler that:
   * 1) Finds due reminders (PENDING and reminderAt <= now), creates events and attempts delivery.
   * 2) Finds existing undelivered events whose nextRetryAt <= now and retryCount < max retries and retries them.
   * <p>
   * Runs every 'reminders.dispatch-interval-ms' (configurable).
   */
  @Scheduled(fixedDelayString = "${reminders.dispatch-interval-ms:30000}")
  public void processEventsScheduler() {
    processDueReminders();
    retryFailedEvents();
  }

  /**
   * A: dispatch newly due reminders
   */
  private void processDueReminders() {
    Instant now = Instant.now();

    List<Reminder> dueReminders;
    try {
      dueReminders = reminderRepository.findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(
        ReminderStatus.PENDING, now);
    } catch (Exception e) {
      log.error("Failed fetching due reminders at {}: {}", now, e.getMessage(), e);
      dueReminders = Collections.emptyList();
    }

    if (dueReminders == null || dueReminders.isEmpty()) return;

    log.info("Found {} due reminders at {}", dueReminders.size(), now);

    for (Reminder r : dueReminders) {
      try {
        recordAndBroadcastReminderDue(r);
      } catch (Exception e) {
        log.error("Failed processing due reminder {}: {}", r.getId(), e.getMessage(), e);
      }
    }
  }

  /**
   * B: retry pending events whose nextRetryAt <= now
   */
  private void retryFailedEvents() {
    Instant now = Instant.now();

    List<Event> toRetry;
    try {
      toRetry = eventRepository.findByDeliveredFalseAndRetryCountLessThanAndNextRetryAtLessThanEqualOrderByTriggeredAtAsc(
        EventConstants.MAX_RETRIES, now);
    } catch (Exception e) {
      log.error("Failed fetching events for retry at {}: {}", now, e.getMessage(), e);
      toRetry = Collections.emptyList();
    }

    if (toRetry == null || toRetry.isEmpty()) return;

    log.info("Retrying {} pending events at {}", toRetry.size(), now);

    for (Event event : toRetry) {
      try {
        if (event.getReminderId() == null || event.getReminderId().isBlank()) {
          log.debug("Skipping retry for event {} (no reminderId, e.g. INVENTORY_LOW)", event.getId());
          continue;
        }
        Optional<Reminder> reminderOpt;
        try {
          reminderOpt = reminderRepository.findById(event.getReminderId());
        } catch (Exception dbEx) {
          log.error("DB error loading reminder {} for retry {}: {}", event.getReminderId(), event.getId(), dbEx.getMessage(), dbEx);
          // DB error — skip this event for now
          continue;
        }

        if (!reminderOpt.isPresent()) {
          log.warn("Skipping retry for event {} because reminder {} not found", event.getId(), event.getReminderId());
          // stop retrying missing reminder by marking delivered
          try {
            event.setDelivered(true);
            event.setDeliveredAt(Instant.now());
            eventRepository.save(event);
          } catch (Exception saveEx) {
            log.error("Failed marking event {} delivered while skipping missing reminder: {}", event.getId(), saveEx.getMessage(), saveEx);
          }
          continue;
        }

        Reminder reminder = reminderOpt.get();

        boolean delivered = attemptBroadcastAndUpdate(event, reminder);
        if (delivered) {
          try {
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setUpdatedAt(Instant.now());
            reminderRepository.save(reminder);
          } catch (Exception saveEx) {
            log.error("Failed marking reminder {} SENT after retry-delivery: {}", reminder.getId(), saveEx.getMessage(), saveEx);
          }
        } else {
          if (event.getRetryCount() >= EventConstants.MAX_RETRIES) {
            log.warn("Event {} exhausted retries ({}). Needs manual handling.", event.getId(), event.getRetryCount());
            try {
              event.setNextRetryAt(Instant.now().plus(Duration.ofDays(EventConstants.EXHAUSTED_RETRY_DAYS)));
              eventRepository.save(event);
            } catch (Exception saveEx) {
              log.error("Failed updating exhausted event {}: {}", event.getId(), saveEx.getMessage(), saveEx);
            }
          }
        }
      } catch (Exception ex) {
        log.error("Failed retrying event {}: {}", event.getId(), ex.getMessage(), ex);
      }
    }
  }

  public void recordAndBroadcastInventoryLow(InventoryLowEventDto dto) {
    Event event = eventMapper.toEventFromInventoryLow(dto);
    Event saved = eventRepository.save(event);

    broadcastInventoryLow(saved, dto);
  }

  private void broadcastInventoryLow(Event event, InventoryLowEventDto dto) {

    List<SseEmitter> emitters = emittersByShop.get(event.getShopId());
    if (emitters == null || emitters.isEmpty()) {
      log.info("No active emitters for shop {}", event.getShopId());
      return;
    }

    for (SseEmitter emitter : emitters) {
      try {

        emitter.send(
          SseEmitter.event()
            .name("INVENTORY_LOW")
            .id(event.getId())
            .data(dto)
        );

      } catch (IOException ex) {
        log.warn("Emitter dead for shop {} — removing", event.getShopId());
        SseEmitterUtils.safeComplete(emitter);
        removeEmitter(event.getShopId(), emitter);
      } catch (Exception ex) {
        log.error("Unexpected SSE error", ex);
        SseEmitterUtils.safeComplete(emitter);
        removeEmitter(event.getShopId(), emitter);
      }
    }
  }
}
