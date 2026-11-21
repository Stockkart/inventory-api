package com.inventory.credit.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Builder;

/**
 * A charge that raises what a customer owes the shop, or what the shop owes a vendor. Recorded
 * through {@link CreditLedger}.
 *
 * @param partyType customer or vendor
 * @param partyId customer or vendor id from the user module
 * @param partyDisplayName name shown on the credit account
 * @param partyPhone phone shown on the credit account; may be null
 * @param amount amount charged, at least 0.01
 * @param note free text shown on the entry; may be null
 * @param referenceType what the charge is for, e.g. {@code SALE} or {@code PURCHASE}
 * @param referenceId id of that document
 * @param sourceKey idempotency key, e.g. {@code SALE:CREDIT:<purchaseId>}; a second charge with
 *     the same key returns the first entry
 * @param txnDate business date; null means today
 */
@Builder
public record CreditCharge(
    CreditPartyType partyType,
    String partyId,
    String partyDisplayName,
    String partyPhone,
    BigDecimal amount,
    String note,
    String referenceType,
    String referenceId,
    String sourceKey,
    LocalDate txnDate) {}
