package com.inventory.notifications.rest.mapper;

import com.inventory.notifications.domain.model.Reminder;
import com.inventory.notifications.rest.dto.ReminderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ReminderMapper {

    @Mapping(target = "reminderId", source = "id")
    ReminderResponse toResponse(Reminder reminder);
}

