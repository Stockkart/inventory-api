package com.inventory.accounting.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Confirms which {@code shopId} the API used for ledger queries — helps debug mismatches with
 * Mongo Compass or multi-shop headers.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountingShopSummaryDto {
  private String shopId;
  /** Rows in {@code acct_gl_accounts} (chart seeded at bootstrap — not transactional postings). */
  private long chartAccountCount;
  /** Rows in {@code acct_journal_entries} (actual ledger postings). */
  private long journalEntryCount;
}
