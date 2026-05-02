package com.inventory.accounting.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Subledger (subsidiary) line keyed by party — mirrors semantics of party postings from the GL legs
 * that carry {@link JournalLine#getPartyType()} / {@link JournalLine#getPartyId()}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "acct_subledger_entries")
@CompoundIndex(name = "shop_party_time", def = "{'shopId': 1, 'partyType': 1, 'partyId': 1, 'postedAt': -1}")
public class SubledgerEntry {

  @Id private String id;
  private String shopId;

  /** Originating journal (for drill-down). */
  private String journalEntryId;
  /** Index of matching line inside the journal document. */
  private int journalLineNo;

  private PartyType partyType;
  private String partyId;

  private SubledgerEntryKind kind;
  /** Always stored &gt; 0 — sign comes from {@link #kind}. */
  private BigDecimal amount;

  private String memo;

  /** Denormalised from journal header for sorting / statements. */
  private Instant journalDate;

  private Instant postedAt;

  /** Same as originating journal {@link JournalEntry#getPostedByUserId()} when manual. */
  private String postedByUserId;

  /** {@link JournalEntry#getSourceKey()} copied for auditing. */
  private String journalSourceKey;
}
