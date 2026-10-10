package com.inventory.documentservice.domain;

/**
 * What a dot-matrix document is, as the print bridge is told it. The bridge picks the page
 * length from this: an estimate uses the shorter estimate form, everything else the bill form.
 *
 * <p>Decided by {@code InvoiceTextRenderer}, the same code that decides whether the text it
 * renders reads "Estimate No." or "Invoice:", so the label on the paper and the page it is
 * printed on cannot disagree.
 */
public enum DotMatrixDocumentKind {
  INVOICE,
  ESTIMATE,
  CREDIT_NOTE,
  DEBIT_NOTE
}
