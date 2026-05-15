package com.inventory.accounting.api;

import com.inventory.accounting.domain.model.JournalEntry;
import java.util.Optional;

/**
 * Service Provider Interface that other modules (product, credit, etc.) use to push journal
 * entries into the accounting ledger. Implementations must be called from within the caller's
 * own {@code @Transactional} boundary so source-of-truth and ledger writes commit atomically.
 *
 * <p>Idempotency: posting twice with the same {@code (sourceType, sourceId)} returns the
 * already-posted entry without creating a duplicate.
 */
public interface AccountingFacade {

  /**
   * Post a hand-built journal entry. Used by {@code MANUAL} entries and by upstream modules that
   * need fully custom postings.
   */
  JournalEntry post(String shopId, String userId, PostJournalRequest req);

  /** High-level helper for {@code VENDOR_PURCHASE_INVOICE}. */
  JournalEntry postVendorPurchaseInvoice(
      String shopId, String userId, VendorPurchaseInvoicePostingRequest req);

  /** High-level helper for a completed sale ({@code SALE}). */
  JournalEntry postSale(String shopId, String userId, SaleInvoicePostingRequest req);

  /** Pay down vendor payable: Dr Sundry Creditors, Cr Cash/Bank or Discount Received. */
  JournalEntry postVendorPayment(String shopId, String userId, PartySettlementPostingRequest req);

  /** Customer pays receivable: Dr Cash/Bank or Bad Debts, Cr Sundry Debtors. */
  JournalEntry postCustomerSettlement(
      String shopId, String userId, PartySettlementPostingRequest req);

  /**
   * Increase vendor payable (credit charge): Dr Purchases, Cr Sundry Creditors [VENDOR].
   * Skipped by the orchestrator when the charge is already represented on a purchase invoice JE.
   */
  JournalEntry postVendorCreditCharge(String shopId, String userId, PartyCreditChargePostingRequest req);

  /**
   * Increase customer receivable (credit charge): Dr Sundry Debtors [CUSTOMER], Cr Sales.
   * Skipped when the charge is already represented on a sale invoice JE.
   */
  JournalEntry postCustomerCreditCharge(
      String shopId, String userId, PartyCreditChargePostingRequest req);

  /**
   * Create a reversal entry mirroring {@code originalEntryId} with sign-flipped lines.
   * The original entry is marked {@link com.inventory.accounting.domain.model.JournalStatus#REVERSED}.
   */
  JournalEntry reverse(String shopId, String userId, String originalEntryId, String reason);

  /** Returns the already-posted entry, if any, for a given source. */
  Optional<JournalEntry> findBySource(
      String shopId,
      com.inventory.accounting.domain.model.JournalSource sourceType,
      String sourceId);
}
