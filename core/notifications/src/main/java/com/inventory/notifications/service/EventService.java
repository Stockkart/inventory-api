package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Event;
import com.inventory.notifications.domain.model.EventType;
import com.inventory.notifications.domain.model.EventStatus;
import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderStatus;
import com.inventory.notifications.domain.repository.EventRepository;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import com.inventory.notifications.util.DbSafetyUtils;
import com.inventory.notifications.util.EventUpdateHelper;
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

  // support multiple tabs/devices per shop
  private final Map<String, List<SseEmitter>> emittersByShop = new ConcurrentHashMap<>();

  private static final int MAX_RETRIES = 3;
  private static final long BASE_RETRY_SECONDS = 10L; // base for exponential backoff
  private static final long MAX_BACKOFF_SECONDS = Duration.ofDays(1).getSeconds();

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
    List<Event> pending = DbSafetyUtils.safeList(
      () -> eventRepository.findByShopIdAndDeliveredFalseOrderByTriggeredAtAsc(shopId),
      "Failed loading pending events for shop " + shopId
    );

    if (pending == null || pending.isEmpty()) return;

    log.info("Replaying {} pending events for shopId={}", pending.size(), shopId);

    for (Event event : pending) {
      try {
        Optional<Reminder> reminderOpt = DbSafetyUtils.safeGet(
          () -> reminderRepository.findById(event.getReminderId()).orElse(null),
          "Failed loading reminder " + event.getReminderId()
        );

        if (reminderOpt == null) { // safeGet returned empty Optional due to DB error
          continue;
        }

        Reminder reminder = reminderOpt.orElse(null);
        if (reminder == null) {
          log.warn("Skipping pending event {} because reminder {} not found", event.getId(), event.getReminderId());
          // optionally mark event delivered to stop infinite retry for missing reminder
          EventUpdateHelper.markDelivered(event, eventRepository);
          continue;
        }

        ReminderResponse payload = reminderMapper.toResponse(reminder);
        emitter.send(SseEmitter.event().name("REMINDER_DUE").id(event.getId()).data(payload));

        // mark event delivered
        EventUpdateHelper.markDelivered(event, eventRepository);

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
    Boolean reminderUpdated = DbSafetyUtils.safeRun(() -> {
      reminder.setStatus(ReminderStatus.SENT);
      reminder.setUpdatedAt(now);
      return reminderRepository.save(reminder);
    }, "Failed marking reminder " + reminder.getId() + " SENT before event creation") != null;

    if (!Boolean.TRUE.equals(reminderUpdated)) {
      log.error("Aborting event creation for reminder {} because reminder update failed", reminder.getId());
      return;
    }

    // 2) Build and persist event (event references the reminder which is now marked SENT)
    Event event = Event.builder()
      .reminderId(reminder.getId())
      .shopId(reminder.getShopId())
      .type(EventType.valueOf(reminder.getType().name()))
      .statusAtTrigger(EventStatus.valueOf(reminder.getStatus().name()))
      .triggeredAt(now)
      .notes(reminder.getNotes())
      .delivered(false)
      .retryCount(0)
      .build();

    Event saved = DbSafetyUtils.safeGet(() -> eventRepository.save(event), "Failed saving event for reminder " + reminder.getId())
      .orElse(null);

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
        EventUpdateHelper.markDelivered(event, eventRepository);
      }
      return true;
    }

    // failed delivery -> increment retry metadata with exponential backoff
    try {
      int nextRetryCount = event.getRetryCount() + 1;
      long backoffSeconds = (long) (BASE_RETRY_SECONDS * Math.pow(2, Math.max(0, nextRetryCount - 1)));
      if (backoffSeconds > MAX_BACKOFF_SECONDS) backoffSeconds = MAX_BACKOFF_SECONDS;

      event.setRetryCount(nextRetryCount);
      event.setLastAttemptAt(Instant.now());
      event.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds));

      DbSafetyUtils.safeRun(() -> eventRepository.save(event), "Failed updating retry metadata for event " + event.getId());
    } catch (Exception ex) {
      log.error("Failed to update retry metadata for event {}: {}", event.getId(), ex.getMessage(), ex);
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

    ReminderResponse payload = reminderMapper.toResponse(reminder);
    boolean deliveredToAtLeastOne = false;

    for (SseEmitter emitter : emitters) {
      try {
        emitter.send(SseEmitter.event().name("REMINDER_DUE").id(event.getId()).data(payload));
        deliveredToAtLeastOne = true;
      } catch (IOException ex) {
        log.warn("Failed to send SSE to shopId={} emitter: {}", shopId, ex.getMessage());
        // emitter appears dead — clean up
        emitter.complete();
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
   * 2) Finds existing undelivered events whose nextRetryAt <= now and retryCount < MAX_RETRIES and retries them.
   * <p>
   * Runs every 'reminders.dispatch-interval-ms' (configurable).
   */
  @Scheduled(fixedDelayString = "${reminders.dispatch-interval-ms:30000}")
  public void processEventsScheduler() {
    Instant now = Instant.now();

    // -- A: dispatch newly due reminders --
    List<Reminder> dueReminders = DbSafetyUtils.safeList(
      () -> reminderRepository.findTop100ByStatusAndReminderAtLessThanEqualOrderByReminderAtAsc(ReminderStatus.PENDING, now),
      "Failed fetching due reminders at " + now
    );

    if (dueReminders != null && !dueReminders.isEmpty()) {
      log.info("Found {} due reminders at {}", dueReminders.size(), now);
      for (Reminder r : dueReminders) {
        try {
          recordAndBroadcastReminderDue(r);
        } catch (Exception e) {
          log.error("Failed processing due reminder {}: {}", r.getId(), e.getMessage(), e);
        }
      }
    }

    // -- B: retry pending events whose nextRetryAt <= now --
    List<Event> toRetry = DbSafetyUtils.safeList(
      () -> eventRepository.findByDeliveredFalseAndRetryCountLessThanAndNextRetryAtLessThanEqualOrderByTriggeredAtAsc(MAX_RETRIES, now),
      "Failed fetching events for retry at " + now
    );

    if (toRetry == null || toRetry.isEmpty()) return;

    log.info("Retrying {} pending events at {}", toRetry.size(), now);

    for (Event event : toRetry) {
      try {
        Optional<Reminder> reminderOpt = DbSafetyUtils.safeGet(
          () -> reminderRepository.findById(event.getReminderId()).orElse(null),
          "DB error loading reminder " + event.getReminderId() + " for retry " + event.getId()
        );
        if (reminderOpt == null) continue;
        Reminder reminder = reminderOpt.orElse(null);

        if (reminder == null) {
          log.warn("Skipping retry for event {} because reminder {} not found", event.getId(), event.getReminderId());
          EventUpdateHelper.markDelivered(event, eventRepository); // stop retrying missing reminder
          continue;
        }

        boolean delivered = attemptBroadcastAndUpdate(event, reminder);
        if (delivered) {
          DbSafetyUtils.safeRun(() -> {
            reminder.setStatus(ReminderStatus.SENT);
            reminder.setUpdatedAt(Instant.now());
            return reminderRepository.save(reminder);
          }, "Failed marking reminder " + reminder.getId() + " SENT after retry-delivery");
        } else {
          if (event.getRetryCount() >= MAX_RETRIES) {
            log.warn("Event {} exhausted retries ({}). Needs manual handling.", event.getId(), event.getRetryCount());
            DbSafetyUtils.safeRun(() -> {
              event.setNextRetryAt(Instant.now().plus(Duration.ofDays(30)));
              return eventRepository.save(event);
            }, "Failed updating exhausted event " + event.getId());
          }
        }
      } catch (Exception ex) {
        log.error("Failed retrying event {}: {}", event.getId(), ex.getMessage(), ex);
      }
    }
  }
}
