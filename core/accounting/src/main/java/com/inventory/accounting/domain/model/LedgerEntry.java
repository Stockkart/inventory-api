package com.inventory.accounting.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Denormalized posting row: one document per {@link JournalLine}, indexed for fast per-account
 * ledger reads. {@code balanceAfter} is precomputed in {@code NormalBalance}-aware sign so trial
 * balance and ledger views need no aggregation pass over journal entries.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "ledger_entries")
@CompoundIndex(
    name = "shop_account_time",
    def = "{'shopId': 1, 'accountId': 1, 'postedAt': -1}")
@CompoundIndex(
    name = "shop_party_time",
    def = "{'shopId': 1, 'partyType': 1, 'partyRefId': 1, 'postedAt': -1}",
    sparse = true)
@CompoundIndex(
    name = "shop_journal",
    def = "{'shopId': 1, 'journalEntryId': 1}")
@CompoundIndex(
    name = "shop_account_date",
    def = "{'shopId': 1, 'accountId': 1, 'txnDate': 1}")
public class LedgerEntry {

  @Id private String id;

  private String shopId;
  private String accountId;
  private String accountCode;
  private String accountName;
  private AccountType accountType;
  private NormalBalance normalBalance;

  private String journalEntryId;
  private String journalEntryNo;
  private int lineIndex;

  private JournalSource sourceType;
  private String sourceId;

  private LocalDate txnDate;
  private Instant postedAt;

  private BigDecimal debit;
  private BigDecimal credit;

  /** Running balance after this posting, signed positive on the account's normal side. */
  private BigDecimal balanceAfter;

  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;

  private String narration;
}
