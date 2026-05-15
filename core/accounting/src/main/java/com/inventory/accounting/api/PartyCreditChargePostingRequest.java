package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;
import lombok.Data;

/** Input for manual / standalone credit charge journal templates. */
@Data
@Builder
public class PartyCreditChargePostingRequest {

  /** Idempotency handle — becomes {@code JournalEntry.sourceId}. */
  private String sourceId;

  private LocalDate txnDate;

  private BigDecimal amount;

  private String partyId;
  private String partyDisplayName;

  private String narration;
}
