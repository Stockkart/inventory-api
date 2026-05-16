package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.JournalSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row in a party statement. Differs from {@link LedgerEntryResponse} by also carrying the
 * party-oriented running balance ({@code balanceAfter}) so the FE doesn't have to recompute it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartyStatementEntryResponse {
  private String id;
  private String journalEntryId;
  private String journalEntryNo;
  private JournalSource sourceType;
  private String sourceId;
  private LocalDate txnDate;
  private Instant postedAt;
  private String accountId;
  private String accountCode;
  private String accountName;
  private BigDecimal debit;
  private BigDecimal credit;
  private BigDecimal balanceAfter;
  private String narration;
}
