package com.inventory.credit.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A customer's or vendor's credit account, as other modules read it: every field of the stored
 * account.
 *
 * @param id account id
 * @param shopId shop
 * @param partyType customer or vendor
 * @param partyRefId customer or vendor id
 * @param partyDisplayName name shown on the account
 * @param partyPhone phone shown on the account
 * @param currentBalance what is owed now
 * @param status whether anything is owed
 * @param createdAt when the account was opened
 * @param updatedAt when it last changed
 * @param lastEntryAt when its last entry was recorded
 */
public record CreditAccountView(
    String id,
    String shopId,
    CreditPartyType partyType,
    String partyRefId,
    String partyDisplayName,
    String partyPhone,
    BigDecimal currentBalance,
    CreditBalanceStatus status,
    Instant createdAt,
    Instant updatedAt,
    Instant lastEntryAt) {}
