package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** Input for vendor payment or customer settlement journal templates. */
@Data
@Builder
public class PartySettlementPostingRequest {

  /** Idempotency handle — becomes {@code JournalEntry.sourceId}. */
  private String sourceId;

  private LocalDate txnDate;

  /** {@code CASH}, {@code UPI}, {@code BANK}, {@code CARD}, {@code ADJUSTMENT}. */
  private String paymentMethod;

  private BigDecimal amount;

  private String partyId;
  private String partyDisplayName;

  private String narration;
  private String bankRef;
}
