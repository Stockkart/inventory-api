package com.inventory.notifications.rest.mapper;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
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
  @Mapping(target = "snoozeDays", ignore = true)
  @Mapping(target = "status", constant = "PENDING")
  @Mapping(target = "endDate", source = "expiryDate")
  @Mapping(target = "notes", ignore = true)
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  Reminder toReminder(String shopId, String inventoryId, Instant reminderAt, Instant expiryDate);

//  @Mapping(target = "snoozeDays", source = "request.snoozeDays")
//  @Mapping(target = "status", constant = "SNOOZED")
//  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
//  Reminder updateReminder(Reminder reminder, SnoozeReminderRequest request);
}