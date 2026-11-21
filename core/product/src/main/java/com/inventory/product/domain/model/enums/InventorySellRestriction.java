package com.inventory.product.domain.model.enums;

/**
 * Controls whether inventory lots may be sold on tax invoices ({@code DocumentType#SALE}) or only
 * on sell-estimate documents.
 */
public enum InventorySellRestriction {
  /** May be sold on quotations, estimates, and completed bills per billing-mode rules. */
  ANY,
  /** Created from a locked stock-entry estimate; sell only via {@code DocumentType#ESTIMATE}. */
  ESTIMATE_ONLY
}
