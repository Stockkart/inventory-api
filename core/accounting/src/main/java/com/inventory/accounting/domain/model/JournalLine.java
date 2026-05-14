package com.inventory.accounting.domain.model;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One leg of a double-entry posting. Exactly one of {@code debit}/{@code credit} is positive; the
 * other is zero. Sub-ledger drill-down is supported via {@code partyType} + {@code partyRefId}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalLine {

  private int lineIndex;
  private String accountId;
  private String accountCode;
  private String accountName;

  private BigDecimal debit;
  private BigDecimal credit;

  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;

  /** Free-text per-line memo (defaults to entry narration when blank). */
  private String memo;
}
