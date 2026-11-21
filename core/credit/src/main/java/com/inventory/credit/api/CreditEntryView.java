package com.inventory.credit.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of a credit account's ledger, as other modules read it: every field of the stored
 * entry.
 *
 * @param id entry id
 * @param txnId human-readable transaction id; may be null on old entries
 * @param shopId shop
 * @param accountId credit account the entry belongs to
 * @param partyType customer or vendor
 * @param partyRefId customer or vendor id
 * @param entryType charge, settlement, return and so on
 * @param direction whether the entry raises or lowers what is owed
 * @param amount amount, always positive
 * @param balanceAfter account balance after this entry
 * @param note free text
 * @param referenceType what the entry is for, e.g. {@code SALE}
 * @param referenceId id of that document
 * @param sourceKey idempotency key
 * @param paymentMethod how a settlement was paid
 * @param bankRef bank or UPI reference of a settlement
 * @param txnDate business date
 * @param createdByUserId who recorded it
 * @param createdAt when it was recorded
 */
public record CreditEntryView(
    String id,
    String txnId,
    String shopId,
    String accountId,
    CreditPartyType partyType,
    String partyRefId,
    CreditEntryType entryType,
    CreditDirection direction,
    BigDecimal amount,
    BigDecimal balanceAfter,
    String note,
    String referenceType,
    String referenceId,
    String sourceKey,
    String paymentMethod,
    String bankRef,
    LocalDate txnDate,
    String createdByUserId,
    Instant createdAt) {}
