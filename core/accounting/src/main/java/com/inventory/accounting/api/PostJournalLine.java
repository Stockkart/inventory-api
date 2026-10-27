package com.inventory.accounting.api;

import java.math.BigDecimal;
import lombok.Builder;

/**
 * One line for {@link PostJournalRequest}. Identify the target account via {@code accountCode}
 * (resolves through the chart of accounts), or pass {@code accountId} directly. Exactly one of
 * {@code debit} / {@code credit} must be positive.
 */
@Builder(toBuilder = true)
public record PostJournalLine(
    /** Preferred way to reference an account. */
    String accountCode,
    /** Alternative; takes precedence over {@code accountCode} if set. */
    String accountId,
    BigDecimal debit,
    BigDecimal credit,
    PartyType partyType,
    String partyRefId,
    String partyDisplayName,
    String memo) {

  /** This line with the party (customer, vendor or shop) it is booked against. */
  public PostJournalLine withParty(PartyType type, String refId, String displayName) {
    return toBuilder().partyType(type).partyRefId(refId).partyDisplayName(displayName).build();
  }
}
