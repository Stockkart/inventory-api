package com.inventory.accounting.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/** Input for vendor payment or customer settlement journal templates. */
@Builder
public record PartySettlementPostingRequest(
    /** Idempotency handle — becomes {@code JournalEntry.sourceId}. */
    String sourceId,
    LocalDate txnDate,
    /** {@code CASH}, {@code UPI}, {@code BANK}, {@code CARD}, {@code ADJUSTMENT}. */
    String paymentMethod,
    BigDecimal amount,
    String partyId,
    String partyDisplayName,
    String narration,
    String bankRef) {}
