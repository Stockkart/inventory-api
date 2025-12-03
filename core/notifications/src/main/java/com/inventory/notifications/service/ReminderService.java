package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

  @Autowired
  private final ReminderRepository reminderRepository;

  @Autowired
  private final ReminderMapper reminderMapper;

  private static final long REMINDER_DAYS_BEFORE_EXPIRY = 7; // Remind 7 days before expiry

  public ReminderListResponse list(String shopId) {
    return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
  }

  public boolean createReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate) {
    Reminder reminder = reminderMapper.toReminder(shopId, inventoryId, reminderAt, expiryDate);
    reminderRepository.save(reminder);
    return true;
  }

  /**
   * Creates a reminder for inventory expiry date if expiry date is provided.
   * Calculates reminder date as 7 days before expiry and only creates if reminder date is in the future.
   * This method runs asynchronously and does not block the caller.
   *
   * @param shopId the shop ID
   * @param inventoryId the inventory lot ID
   * @param expiryDate the expiry date of the inventory (can be null)
   */
  @Async
  public void createReminderForExpiry(String shopId, String inventoryId, Instant expiryDate) {
    if (expiryDate == null) {
      return;
    }

    try {
      // Calculate reminder date (7 days before expiry)
      Instant reminderAt = expiryDate.minus(Duration.ofDays(REMINDER_DAYS_BEFORE_EXPIRY));

      // Only create reminder if reminder date is in the future
      if (reminderAt.isAfter(Instant.now())) {
        createReminder(shopId, inventoryId, reminderAt, expiryDate);
        log.info("Created reminder for inventory lot: {} with reminder date: {} and expiry date: {}",
                inventoryId, reminderAt, expiryDate);
      } else {
        log.warn("Skipping reminder creation for inventory lot: {} - reminder date {} is in the past",
                inventoryId, reminderAt);
      }
    } catch (Exception e) {
      log.error("Failed to create reminder for inventory lot: {} - {}", inventoryId, e.getMessage(), e);
      // Don't fail the inventory creation if reminder creation fails
    }
  }

  public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
    Reminder reminder = reminderRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));

    Reminder updatedReminder = reminderMapper.updateReminder(reminder, id, request);
    reminderRepository.save(updatedReminder);

    return reminderMapper.toResponse(updatedReminder);
  }
}

