package com.inventory.product.domain.model.enums;

/** Lifecycle for stock-in (product entry) estimate drafts. */
public enum StockEntryEstimateState {
  /** Editable draft; no inventory yet. */
  OPEN,
  /** Locked: BASIC inventory created; estimate-only sell path. */
  LOCKED,
  /** Converted into a REGULAR product-entry save. */
  CONVERTED,
  /** User discarded; not lockable or convertible. */
  DISCARDED
}
