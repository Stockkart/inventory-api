package com.inventory.notifications.rest.mapper;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.domain.model.ReminderType;
import com.inventory.notifications.rest.dto.CreateReminderRequest;
import com.inventory.notifications.rest.dto.ReminderListResponse;
import com.inventory.notifications.rest.dto.ReminderResponse;
import com.inventory.notifications.rest.dto.SnoozeReminderRequest;
import com.inventory.notifications.rest.dto.UpdateReminderRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.time.Instant;
import java.util.List;

@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface ReminderMapper {

  @Mapping(target = "reminderId", source = "id")
  @Mapping(target = "expiryDate", source = "endDate")
  @Mapping(target = "type", source = "type")
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
  @Mapping(target = "snoozeDays", constant = "0")
  @Mapping(target = "status", constant = "PENDING")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "type", expression = "java(request.getType() != null ? request.getType() : com.inventory.notifications.domain.model.ReminderType.EXPIRY)")
  Reminder toReminder(CreateReminderRequest request);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "snoozeDays", ignore = true)
  @Mapping(target = "status", constant = "PENDING")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  Reminder toReminder(
          String shopId,
          String inventoryId,
          Instant reminderAt,
          Instant endDate,
          String notes,
          ReminderType type
  );

  @Mapping(target = "reminderAt", expression = "java(reminder.getReminderAt() != null ? reminder.getReminderAt().plus(java.time.Duration.ofDays(request.getSnoozeDays())) : null)")
  @Mapping(target = "snoozeDays", expression = "java(reminder.getSnoozeDays() != null ? reminder.getSnoozeDays() + request.getSnoozeDays() : request.getSnoozeDays())")
  @Mapping(target = "status", constant = "SNOOZED")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "inventoryId", ignore = true)
  @Mapping(target = "endDate", ignore = true)
  @Mapping(target = "notes", ignore = true)
  @Mapping(target = "type", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  void updateReminderForSnooze(@MappingTarget Reminder reminder, SnoozeReminderRequest request);

  @Mapping(target = "status", expression = "java(request.getStatus() != null ? com.inventory.notifications.domain.model.ReminderStatus.valueOf(request.getStatus().toUpperCase()) : null)")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", ignore = true)
  @Mapping(target = "inventoryId", ignore = true)
  @Mapping(target = "snoozeDays", ignore = true)
  @Mapping(target = "type", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  void updateReminder(@MappingTarget Reminder reminder, UpdateReminderRequest request);
}

