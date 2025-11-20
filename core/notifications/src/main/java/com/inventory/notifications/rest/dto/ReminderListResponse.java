package com.inventory.notifications.rest.dto;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ReminderListResponse {
    @Singular("reminder")
    List<ReminderResponse> data;
}

