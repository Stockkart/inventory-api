package com.inventory.notifications.rest.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ReminderListResponse {
  private List<ReminderResponse> data;
  private PageMeta meta;
}
