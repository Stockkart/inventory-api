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
