package com.inventory.accounting.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JournalEntriesPageResponse {
  private List<JournalEntryResponse> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
