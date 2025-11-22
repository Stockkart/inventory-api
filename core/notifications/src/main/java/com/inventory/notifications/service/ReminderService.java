package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@AllArgsConstructor
@Transactional
public class ReminderService {

  @Autowired
  private final ReminderRepository reminderRepository;

  @Autowired
  private final ReminderMapper reminderMapper;

  public ReminderListResponse list(String shopId) {
    return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
  }

  public boolean createReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate) {
    Reminder reminder = reminderMapper.toReminder(shopId, inventoryId, reminderAt, expiryDate);
    reminderRepository.save(reminder);
    return true;
  }

  public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
    Reminder reminder = reminderRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));

    Reminder updatedReminder = reminderMapper.updateReminder(reminder, id, request);
    reminderRepository.save(updatedReminder);

    return reminderMapper.toResponse(updatedReminder);
  }
}

