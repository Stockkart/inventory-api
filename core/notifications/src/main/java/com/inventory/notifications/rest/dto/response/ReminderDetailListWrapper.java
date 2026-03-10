package com.inventory.notifications.rest.dto.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class ReminderDetailListWrapper {
  private List<ReminderDetailListResponse> data;
  private PageMeta meta;
}
