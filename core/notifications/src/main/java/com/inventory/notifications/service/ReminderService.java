package com.inventory.notifications.service;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderType;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.*;
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

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

  private static final long REMINDER_DAYS_BEFORE = 15L;

  @Autowired
  private final ReminderRepository reminderRepository;

  @Autowired
  private final ReminderMapper reminderMapper;

  @Autowired
  private final ReminderValidator reminderValidator;

  public ReminderListResponse list(String shopId) {
    return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
  }

  @Async
  public void createReminderForInventoryCreate(CreateReminderForInventoryRequest request) {
    // If neither normal nor custom have the required key dates, skip completely
    if (request.getExpiryDate() == null && request.getReminderEndDate() == null) {
      log.debug("No valid expiryDate or reminderEndDate for inventoryId={}, skipping reminder creation", request.getInventoryId());
      return;
    }

    try {
      // Create expiry reminder if expiry date is provided
      if (request.getExpiryDate() != null) {
        createExpiryReminder(request);
      }

      // Create custom reminder if custom end date is provided
      if (request.getReminderEndDate() != null) {
        createCustomReminder(request);
      }

    } catch (Exception e) {
      log.error("Failed to create reminder(s) for inventory lot {} - {}", request.getInventoryId(), e.getMessage(), e);
      // swallow error so inventory creation doesn't fail
    }
  }

  private void createExpiryReminder(CreateReminderForInventoryRequest request) {
    Instant expiryReminderAt = computeReminderTime(request, ReminderType.EXPIRY);
    createAndSaveReminderIfValid(request, expiryReminderAt, request.getExpiryDate(), ReminderType.EXPIRY, null);
  }

  private void createCustomReminder(CreateReminderForInventoryRequest request) {
    Instant customReminderAt = computeReminderTime(request, ReminderType.CUSTOM);
    createAndSaveReminderIfValid(request, customReminderAt, request.getReminderEndDate(), ReminderType.CUSTOM, request.getReminderNotes());
  }

  // -------- common helper: compute reminderAt with default 15 days before --------
  private Instant computeReminderTime(CreateReminderForInventoryRequest request, ReminderType type) {
    Instant now = Instant.now();
    Instant explicitReminderAt;
    Instant baseDate;

    if (type == ReminderType.EXPIRY) {
      explicitReminderAt = request.getReminderAt();
      baseDate = request.getExpiryDate();
    } else {
      explicitReminderAt = request.getNewReminderAt();
      baseDate = request.getReminderEndDate();
    }

    if (baseDate == null && explicitReminderAt == null) {
      log.debug("No baseDate or explicitReminderAt for {} reminder on inventoryId={}, skipping", type, request.getInventoryId());
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

    log.warn("Computed {} reminderAt {} is null or in the past for inventoryId={}, skipping", type, result, request.getInventoryId());
    return null;
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
