package com.inventory.hr.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** API_CONTRACT PG3 page block; copied from reminders (no pom edge to product or reminders). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageMeta {
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
