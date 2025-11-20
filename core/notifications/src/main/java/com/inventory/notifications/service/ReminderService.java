package com.inventory.notifications.service;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.repository.ReminderRepository;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.mapper.ReminderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final ReminderMapper reminderMapper;

    public ReminderListResponse list(String shopId) {
        List<ReminderResponse> reminders = reminderRepository.findByShopId(shopId).stream()
                .map(reminderMapper::toResponse)
                .toList();
        return ReminderListResponse.builder()
                .data(reminders)
                .build();
    }

    public boolean createReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate) {
        Reminder reminder = Reminder.builder()
                .shopId(shopId)
                .inventoryId(inventoryId)
                .reminderAt(reminderAt)
                .expiryDate(expiryDate)
                .status("PENDING")
                .build();
        reminderRepository.save(reminder);
        return true;
    }

    public ReminderResponse snooze(String id, SnoozeReminderRequest request) {
        Reminder reminder = reminderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Reminder not found"));
        reminder.setSnoozeUntil(request.getSnoozeUntil());
        reminder.setStatus("SNOOZED");
        reminderRepository.save(reminder);
        return reminderMapper.toResponse(reminder);
    }
}

