package com.inventory.user.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerEntriesResponse {

  private List<LedgerEntryDto> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
