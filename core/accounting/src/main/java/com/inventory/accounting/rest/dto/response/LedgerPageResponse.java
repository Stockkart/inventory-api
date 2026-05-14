package com.inventory.accounting.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerPageResponse {
  private AccountResponse account;
  private List<LedgerEntryResponse> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
