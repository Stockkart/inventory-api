package com.inventory.accounting.api;

import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import lombok.Data;

/**
 * One line for {@link PostJournalRequest}. Identify the target account via {@code accountCode}
 * (resolves through the chart of accounts), or pass {@code accountId} directly. Exactly one of
 * {@code debit} / {@code credit} must be positive.
 */
@Data
public class PostJournalLine {

  /** Preferred way to reference an account. */
  private String accountCode;

  /** Alternative; takes precedence over {@code accountCode} if set. */
  private String accountId;

  private BigDecimal debit;
  private BigDecimal credit;

  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;

  private String memo;
}
