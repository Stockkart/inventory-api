package com.inventory.notifications.rest.mapper;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ReminderMapper {

  @Mapping(target = "reminderId", source = "id")
  ReminderResponse toResponse(Reminder reminder);

  default ReminderListResponse toReminderListResponse(List<Reminder> reminders) {
    if (reminders == null) {
      return null;
    }
    ReminderListResponse response = new ReminderListResponse();
    response.setData(reminders.stream()
            .map(this::toResponse)
            .toList());
    return response;
  }

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "snoozeUntil", ignore = true)
  @Mapping(target = "status", constant = "PENDING")
  Reminder toReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate);

  @Mapping(target = "id", source = "id")
  @Mapping(target = "snoozeUntil", source = "request.snoozeUntil")
  @Mapping(target = "status", constant = "SNOOZED")
  Reminder updateReminder(Reminder reminder, String id, SnoozeReminderRequest request);
}

