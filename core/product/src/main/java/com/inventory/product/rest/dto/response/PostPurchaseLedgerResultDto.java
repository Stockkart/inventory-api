package com.inventory.product.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostPurchaseLedgerResultDto {
  private String accountingJournalEntryId;
  /** True when service chose not to post (e.g. zero payable — check message). */
  private boolean skipped;
  private String message;
}
