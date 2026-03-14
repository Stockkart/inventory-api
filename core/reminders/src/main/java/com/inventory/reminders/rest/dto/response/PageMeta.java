package com.inventory.reminders.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageMeta {
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
