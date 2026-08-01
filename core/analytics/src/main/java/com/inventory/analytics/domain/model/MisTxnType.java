package com.inventory.analytics.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Kinds of row that appear in a vendor money ledger.
 *
 * <p>Each constant carries its display label and source-type tag. The wire values are the enum
 * names.
 */
public enum MisTxnType {
  VENDOR_PURCHASE("Purchase", "VENDOR_PURCHASE_INVOICE"),
  VENDOR_RETURN("Return", "VENDOR_PURCHASE_RETURN"),
  VENDOR_PAYMENT("Payment", "VENDOR_PAYMENT"),
  VENDOR_CREDIT_CHARGE("Credit charge", "VENDOR_CREDIT_CHARGE"),
  /** Synthetic carried-forward balance row, not a real transaction. */
  OPENING("Opening", "OPENING");

  private final String label;
  private final String sourceType;

  MisTxnType(String label, String sourceType) {
    this.label = label;
    this.sourceType = sourceType;
  }

  public String label() {
    return label;
  }

  public String sourceType() {
    return sourceType;
  }

  /** Human-facing transaction id — short source id only (type is shown separately). */
  public String txnId(String shortId) {
    return shortId != null ? shortId : "";
  }

  /** Increases what the shop owes the vendor. */
  public boolean increasesPayable() {
    return this == VENDOR_PURCHASE || this == VENDOR_CREDIT_CHARGE || this == OPENING;
  }

  public static Optional<MisTxnType> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
