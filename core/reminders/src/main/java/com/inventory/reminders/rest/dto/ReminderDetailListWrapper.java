package com.inventory.reminders.rest.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ReminderDetailListWrapper {
  private List<ReminderDetailListResponse> data;
  private PageMeta meta;
}
