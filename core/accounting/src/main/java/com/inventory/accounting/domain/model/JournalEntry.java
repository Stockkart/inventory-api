package com.inventory.accounting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Single business event recorded with at least two embedded {@link JournalLine}s where {@code Σ
 * debits == Σ credits}. {@code sourceType + sourceId} are unique per shop, so re-firing the same
 * upstream event is a no-op.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "journal_entries")
@CompoundIndex(
    name = "shop_source_unique",
    def = "{'shopId': 1, 'sourceType': 1, 'sourceId': 1}",
    unique = true,
    sparse = true)
@CompoundIndex(name = "shop_txn_date", def = "{'shopId': 1, 'txnDate': -1}")
@CompoundIndex(
    name = "shop_entry_no",
    def = "{'shopId': 1, 'entryNo': 1}",
    unique = true,
    sparse = true)
public class JournalEntry {

  @Id private String id;

  private String shopId;

  /** Auto-generated, monotonic per shop (e.g. {@code JE-000123}). */
  private String entryNo;

  /** Business date (shop timezone) used for reporting and period bucketing. */
  private LocalDate txnDate;

  /** Server-side instant the entry was committed. */
  private Instant postedAt;

  private JournalSource sourceType;

  /** External event id (e.g. vendor purchase invoice id) — unique per source per shop. */
  private String sourceId;

  /** Optional caller-supplied idempotency key (kept for diagnostics). */
  private String sourceKey;

  private JournalStatus status;

  /** If this entry was created by reversing another entry, points back to it. */
  private String reversesEntryId;

  /** If this entry has been reversed, points to the reversal entry. */
  private String reversedByEntryId;

  private String narration;

  private List<JournalLine> lines = new ArrayList<>();

  private BigDecimal totalDebit;
  private BigDecimal totalCredit;

  private String createdByUserId;
}
