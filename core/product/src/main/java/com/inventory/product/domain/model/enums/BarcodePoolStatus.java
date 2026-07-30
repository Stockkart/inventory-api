package com.inventory.product.domain.model.enums;

/** Lifecycle of a shop barcode in the printable pool. */
public enum BarcodePoolStatus {
  /** Generated but not yet linked to a catalog product. */
  UNUSED,
  /** Linked to a {@code Product} via {@code productId}. */
  ATTACHED
}
