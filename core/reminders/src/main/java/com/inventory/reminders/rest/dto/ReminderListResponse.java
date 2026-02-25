package com.inventory.reminders.rest.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ReminderListResponse {
  private List<ReminderResponse> data;
  private PageMeta meta;
}

