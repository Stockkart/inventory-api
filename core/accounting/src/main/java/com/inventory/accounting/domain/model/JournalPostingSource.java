package com.inventory.accounting.domain.model;

public enum JournalPostingSource {
  /** User-entered adjusting or cash entries. */
  MANUAL,
  /** Legacy / miscellaneous automation hooks. */
  SYSTEM,
  /** Posted when a retail sale completes (checkout COMPLETED). */
  SALE,
  /** Posted when bulk stock-in is saved against a vendor purchase invoice. */
  PURCHASE
}
