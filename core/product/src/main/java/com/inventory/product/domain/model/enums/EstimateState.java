package com.inventory.product.domain.model.enums;

/** Lifecycle for {@link DocumentType#ESTIMATE} documents only. */
public enum EstimateState {
  /** Editable; may be printed and converted. */
  OPEN,
  /** Locked after convert-to-invoice; reprint only. */
  CONVERTED,
  /** User discarded; not convertible. */
  DISCARDED
}
