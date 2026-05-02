package com.inventory.accounting.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** One leg of a double-entry journal, embedded inside {@link JournalEntry}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JournalLine {

  private int lineNo;
  /** Refers to {@link GlAccount#getId()} */
  private String accountId;
  private String accountCode;
  private BigDecimal debit;
  private BigDecimal credit;
  private String memo;
  /** When set together with {@link #partyId}, a matching subledger row is written. */
  private PartyType partyType;
  private String partyId;
}
