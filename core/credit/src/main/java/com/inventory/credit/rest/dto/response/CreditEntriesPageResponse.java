package com.inventory.credit.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreditEntriesPageResponse {

  private List<CreditEntryResponse> entries;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
}
