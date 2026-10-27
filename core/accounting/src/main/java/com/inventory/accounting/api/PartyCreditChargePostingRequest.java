package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/** Input for manual / standalone credit charge journal templates. */
@Builder
public record PartyCreditChargePostingRequest(
    /** Idempotency handle — becomes {@code JournalEntry.sourceId}. */
    String sourceId,
    LocalDate txnDate,
    BigDecimal amount,
    String partyId,
    String partyDisplayName,
    String narration) {}
