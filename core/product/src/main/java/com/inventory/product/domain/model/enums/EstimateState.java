package com.inventory.product.domain.model.enums;

/** Lifecycle for {@link DocumentType#ESTIMATE} documents only. */
public enum EstimateState {
  /** Editable draft; soft-reserves stock. */
  OPEN,
  /** Finalized quote; printable; convert rules depend on line inventory mode. */
  LOCKED,
  /** Converted to a sale cart; reprint only. */
  CONVERTED,
  /** User discarded; not convertible. */
  DISCARDED
}
