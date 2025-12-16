package com.inventory.notifications.service;

import com.inventory.common.dto.CustomReminderRequest;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderType;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.notifications.rest.dto.CreateReminderRequest;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.dto.UpdateReminderRequest;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import com.inventory.notifications.validation.ReminderValidator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

  private static final long REMINDER_DAYS_BEFORE = 15L;

  @Autowired
  private ReminderRepository reminderRepository;

  @Autowired
  private ReminderMapper reminderMapper;

  @Autowired
  private ReminderValidator reminderValidator;

  public ReminderListResponse list(String shopId) {
    return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
  }

  @Async
  public void createReminderForInventoryCreate(CreateReminderForInventoryRequest request) {
    // Check if there's anything to create
    boolean hasExpiryReminder = request.getExpiryDate() != null;
    boolean hasCustomReminders = request.getCustomReminders() != null && !request.getCustomReminders().isEmpty();

    if (!hasExpiryReminder && !hasCustomReminders) {
      log.debug("No valid expiryDate or customReminders for inventoryId={}, skipping reminder creation", request.getInventoryId());
      return;
    }

    try {
      // Create expiry reminder if expiry date is provided (default reminder)
      if (hasExpiryReminder) {
        createExpiryReminder(request);
      }

      // Create multiple custom reminders if provided
      if (hasCustomReminders) {
        createCustomReminders(request);
      }

    } catch (Exception e) {
      log.error("Failed to create reminder(s) for inventory lot {} - {}", request.getInventoryId(), e.getMessage(), e);
      // swallow error so inventory creation doesn't fail
    }
  }

  private void createExpiryReminder(CreateReminderForInventoryRequest request) {
    Instant expiryReminderAt = computeExpiryReminderTime(request);
    createAndSaveReminderIfValid(
        request,
        expiryReminderAt,
        request.getExpiryDate(),
        ReminderType.EXPIRY,
        "Item Expiring in few days"
    );
  }

  private void createCustomReminders(CreateReminderForInventoryRequest request) {
    List<CustomReminderRequest> customReminders = request.getCustomReminders();
    if (customReminders == null || customReminders.isEmpty()) {
      return;
    }

    for (CustomReminderRequest customReminder : customReminders) {
      Instant customReminderAt = computeCustomReminderTime(customReminder);
      createAndSaveReminderIfValid(
          request,
          customReminderAt,
          customReminder.getEndDate(),
          ReminderType.CUSTOM,
          customReminder.getNotes()
      );
    }
  }

  // -------- helper: compute reminderAt with default 15 days before --------
  private Instant computeReminderTime(Instant explicitReminderAt, Instant baseDate, String contextMessage) {
    Instant now = Instant.now();

    if (baseDate == null && explicitReminderAt == null) {
      log.debug("No baseDate or explicitReminderAt for {}. skipping", contextMessage);
      return null;
    }

    Instant result = explicitReminderAt;
    if (result == null && baseDate != null) {
      // default: 15 days before base date
      result = baseDate.minus(Duration.ofDays(REMINDER_DAYS_BEFORE));
    }

    if (result != null && result.isAfter(now)) {
      return result;
    }

    log.warn("Computed reminderAt {} is null or in the past for {}, skipping", result, contextMessage);
    return null;
  }

  // -------- helper: compute expiry reminderAt with default 15 days before --------
  private Instant computeExpiryReminderTime(CreateReminderForInventoryRequest request) {
    return computeReminderTime(
        request.getReminderAt(),
        request.getExpiryDate(),
        String.format("expiry reminder on inventoryId=%s", request.getInventoryId())
    );
  }

  // -------- helper: compute custom reminderAt with default 15 days before --------
  private Instant computeCustomReminderTime(CustomReminderRequest customReminder) {
    return computeReminderTime(
        customReminder.getReminderAt(),
        customReminder.getEndDate(),
        "custom reminder"
    );
  }

  // -------- common helper: actually create + save reminder if valid --------
  private void createAndSaveReminderIfValid(
      CreateReminderForInventoryRequest request,
      Instant reminderAt,
      Instant endDate,
      ReminderType type,
      String notes
  ) {
    if (reminderAt == null || endDate == null) {
      log.debug("Not creating {} reminder for inventoryId={} because reminderAt or endDate is null",
          type, request.getInventoryId());
      return;
    }

    Reminder reminder = reminderMapper.toReminder(
        request.getShopId(),
        request.getInventoryId(),
        reminderAt,
        endDate,
        notes,
        type
    );

    reminderRepository.save(reminder);

    log.info("Created {} reminder for inventoryId={} with reminderAt={} and endDate={}, notes={}",
        type, request.getInventoryId(), reminderAt, endDate, notes);
  }

  public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
    reminderValidator.validateSnoozeRequest(id, request);

    Reminder reminder = reminderRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Reminder", "id", null));

    reminderMapper.updateReminderForSnooze(reminder, request);
    reminderRepository.save(reminder);

    return reminderMapper.toResponse(reminder);
  }


  // CREATE (manual, no inventory auto-logic)
  public ReminderResponse create(CreateReminderRequest request) {
    reminderValidator.validateCreateRequest(request);

    Reminder reminder = reminderMapper.toReminder(request);
    reminderRepository.save(reminder);

    return reminderMapper.toResponse(reminder);
  }

  // READ (by id)
  public ReminderResponse get(String id) {

    Reminder reminder = reminderRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Reminder", "id", null));

    return reminderMapper.toResponse(reminder);
  }

  // UPDATE (manual)
  public ReminderResponse update(String id, UpdateReminderRequest request) {
    reminderValidator.validateStatus(id, request.getStatus());

    Reminder reminder = reminderRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Reminder", "id", null));

    reminderMapper.updateReminder(reminder, request);
    reminderRepository.save(reminder);

    return reminderMapper.toResponse(reminder);
  }

  // DELETE
  public long delete(String id) {
    return reminderRepository.deleteByIdReturningCount(id);
  }
}
