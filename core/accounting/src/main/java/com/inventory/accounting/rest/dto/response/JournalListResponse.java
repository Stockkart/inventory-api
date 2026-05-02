package com.inventory.accounting.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class JournalListResponse {
  private List<JournalEntryResponse> journals;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
