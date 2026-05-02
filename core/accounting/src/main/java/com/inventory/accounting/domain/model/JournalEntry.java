package com.inventory.accounting.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "acct_journal_entries")
@CompoundIndex(name = "shop_posted_desc", def = "{'shopId': 1, 'postedAt': -1}")
public class JournalEntry {

  @Id private String id;

  @Indexed private String shopId;

  private Instant journalDate;

  /** When the journal was persisted — same as POSTED timestamp for simple mode. */
  private Instant postedAt;

  private String description;

  private JournalPostingSource source;

  /**
   * Idempotency: when non-blank, at most one posted journal per (shopId, sourceKey).
   * Example: "SYSTEM:PURCHASE:inv-123". Uniqueness enforced in service layer.
   */
  private String sourceKey;

  private BigDecimal totalDebitSum;
  private BigDecimal totalCreditSum;

  private String postedByUserId;

  private List<JournalLine> lines = new ArrayList<>();
}
