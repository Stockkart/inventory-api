package com.inventory.notifications.service;

import com.inventory.notifications.rest.dto.*;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderType;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.mapper.ReminderMapper;
import com.inventory.notifications.utils.ReminderUtils;
import com.inventory.notifications.validation.ReminderValidator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@AllArgsConstructor
@Transactional
public class ReminderService {


  @Autowired
  private ReminderRepository reminderRepository;

  @Autowired
  private ReminderMapper reminderMapper;

  @Autowired
  private ReminderValidator reminderValidator;

  public ReminderListResponse list(String shopId, int page, int size) {
    PageRequest pageable = PageRequest.of(page, size);
    Page<Reminder> result =
      reminderRepository.findByShopIdOrderByReminderAtAsc(shopId, pageable);

    return reminderMapper.toReminderListResponse(result);
  }

  public ReminderDetailListWrapper detailList(String shopId, int page, int size) {
    PageRequest pageable = PageRequest.of(page, size);
    Page<Reminder> result = reminderRepository.findByShopIdOrderByReminderAtAsc(shopId, pageable);

    return reminderMapper.toDetailListWrapper(result);
  }

  public ReminderDetailListResponse getDetail(String id) {
    Reminder reminder = reminderRepository.findById(id)
      .orElseThrow(() -> new ResourceNotFoundException("Reminder", "id", id));
    return reminderMapper.toDetailResponse(reminder);
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
    Instant expiryReminderAt = ReminderUtils.computeExpiryReminderTime(request);
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
      Instant customReminderAt = ReminderUtils.computeCustomReminderTime(customReminder);
      createAndSaveReminderIfValid(
        request,
        customReminderAt,
        customReminder.getEndDate(),
        ReminderType.CUSTOM,
        customReminder.getNotes()
      );
    }
  }

  // -------- create + save reminder if valid --------
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
