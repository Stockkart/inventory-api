package com.inventory.accounting.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

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
  JournalEntryView post(String shopId, String userId, PostJournalRequest req);

  /** High-level helper for {@code VENDOR_PURCHASE_INVOICE}. */
  JournalEntryView postVendorPurchaseInvoice(
      String shopId, String userId, VendorPurchaseInvoicePostingRequest req);

  /** High-level helper for a completed sale ({@code SALE}). */
  JournalEntryView postSale(String shopId, String userId, SaleInvoicePostingRequest req);

  /** Customer return / credit note ({@code SALES_RETURN}). */
  JournalEntryView postSalesReturn(String shopId, String userId, SalesReturnPostingRequest req);

  /** Vendor purchase return ({@code VENDOR_PURCHASE_RETURN}). */
  JournalEntryView postVendorPurchaseReturn(
      String shopId, String userId, VendorPurchaseReturnPostingRequest req);

  /** Pay down vendor payable: Dr Sundry Creditors, Cr Cash/Bank or Discount Received. */
  JournalEntryView postVendorPayment(String shopId, String userId, PartySettlementPostingRequest req);

  /** Customer pays receivable: Dr Cash/Bank or Bad Debts, Cr Sundry Debtors. */
  JournalEntryView postCustomerSettlement(
      String shopId, String userId, PartySettlementPostingRequest req);

  /**
   * Increase vendor payable (credit charge): Dr Purchases, Cr Sundry Creditors [VENDOR].
   * Skipped by the orchestrator when the charge is already represented on a purchase invoice JE.
   */
  JournalEntryView postVendorCreditCharge(String shopId, String userId, PartyCreditChargePostingRequest req);

  /**
   * Increase customer receivable (credit charge): Dr Sundry Debtors [CUSTOMER], Cr Sales.
   * Skipped when the charge is already represented on a sale invoice JE.
   */
  JournalEntryView postCustomerCreditCharge(
      String shopId, String userId, PartyCreditChargePostingRequest req);

  /**
   * Create a reversal entry mirroring {@code originalEntryId} with sign-flipped lines.
   * The original entry is marked {@link JournalStatus#REVERSED}.
   */
  JournalEntryView reverse(String shopId, String userId, String originalEntryId, String reason);

  /** Returns the already-posted entry, if any, for a given source. */
  Optional<JournalEntryView> findBySource(
      String shopId,
      JournalSource sourceType,
      String sourceId);

  /**
   * The codes among {@code codes} that the shop holds as an account of its own ({@code system
   * != true}): the seeder skips a code a shop already uses, so posting by such a code would book
   * into the shop's unrelated account. One read; codes the shop has no account for are not
   * returned (posting seeds them as system accounts).
   */
  Set<String> ownAccountCodes(String shopId, Collection<String> codes);

  /**
   * Seeds the standard chart of accounts for a newly registered shop. Codes the shop already
   * holds are left alone, so calling it again is harmless.
   *
   * @return number of accounts created
   */
  int seedChartOfAccounts(String shopId);
}
