package com.inventory.product.domain.model.enums;

/** Which record a dot-matrix print job prints, and so which id {@code documentId} names. */
public enum PrintDocumentSource {
  /** A sale: {@code documentId} is the purchase id. Prints as an invoice or an estimate. */
  SALE,
  /** A customer refund: {@code documentId} is the refund id. Prints as a credit note. */
  REFUND,
  /** A return to a vendor: {@code documentId} is the return id. Prints as a debit note. */
  VENDOR_RETURN
}
