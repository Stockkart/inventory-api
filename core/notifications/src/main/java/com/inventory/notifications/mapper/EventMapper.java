package com.inventory.notifications.mapper;

import com.inventory.notifications.domain.model.Event;
import com.inventory.notifications.domain.model.EventStatus;
import com.inventory.notifications.domain.model.EventType;
import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.rest.dto.response.InventoryLowEventDto;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface EventMapper {

  default Event toEventFromReminderDue(Reminder reminder, Instant triggeredAt) {
    if (reminder == null || reminder.getId() == null) {
      return null;
    }
    return Event.builder()
        .reminderId(reminder.getId())
        .shopId(reminder.getShopId())
        .type(EventType.valueOf(reminder.getType().name()))
        .statusAtTrigger(EventStatus.valueOf(reminder.getStatus().name()))
        .triggeredAt(triggeredAt != null ? triggeredAt : Instant.now())
        .notes(reminder.getNotes())
        .delivered(false)
        .retryCount(0)
        .build();
  }

  default Event toEventFromInventoryLow(InventoryLowEventDto dto) {
    if (dto == null) {
      return null;
    }
    return Event.builder()
        .shopId(dto.getShopId())
        .type(EventType.INVENTORY_LOW)
        .triggeredAt(Instant.now())
        .payloadJson(dto)
        .delivered(false)
        .retryCount(0)
        .build();
  }
}
