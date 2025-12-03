package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.*;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.*;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
@Transactional
public class ReminderService {

  @Autowired
  private final ReminderRepository reminderRepository;

  @Autowired
  private final ReminderMapper reminderMapper;

  public ReminderListResponse list(String shopId) {
    return reminderMapper.toReminderListResponse(reminderRepository.findByShopId(shopId));
  }

  public Reminder createReminder(CreateReminderRequest request, String shopId, String userId) {

    // We build the entity directly to ensure all new fields (notes, snooze) are captured
    Reminder reminder = Reminder.builder()
            .inventoryId(request.getInventoryId())
            .reminderAt(request.getReminderAt())
            .endDate(request.getEndDate()) // Maps the expiry/end date
            .notes(request.getNotes())     // Maps the notes
            .snoozeDays(0)                 // Default
            .status(ReminderStatus.PENDING)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

    return reminderRepository.save(reminder);
  }

//  public boolean createReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate) {
//    Reminder reminder = reminderMapper.toReminder(shopId, inventoryId, reminderAt, expiryDate);
//    reminderRepository.save(reminder);
//    return true;
//  }

//  public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
//    Reminder reminder = reminderRepository.findById(id)
//            .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));
//
//    // Mapper now updates 'reminder' directly
//    reminderMapper.updateReminder(reminder, request);
//
//    // Save the updated entity
//    reminderRepository.save(reminder);
//
//    return reminderMapper.toResponse(reminder);
//  }
}

