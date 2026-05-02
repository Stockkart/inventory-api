package com.inventory.accounting.domain.model;

/**
 * Debit/credit in the subsidiary ledger (not the same meaning as trial-balance debit column).
 *
 * <p>Vendor payable (we owe): DEBIT increases amount owed; CREDIT reduces it.
 *
 * <p>Customer receivable (they owe us): CREDIT increases balance; DEBIT reduces it.
 */
public enum SubledgerEntryKind {
  DEBIT,
  CREDIT
}
