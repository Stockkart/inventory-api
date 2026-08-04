package com.inventory.analytics.domain.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Kinds of row that appear in a sales (customer) money ledger.
 *
 * <p>Customer-side counterpart of {@link MisTxnType}. Each constant carries its display label and
 * source-type tag. The wire values are the enum names.
 */
public enum SalesMisTxnType {
  SALE("Sale", "SALE_INVOICE"),
  SALES_RETURN("Return", "SALES_RETURN"),
  CUSTOMER_RECEIPT("Receipt", "CUSTOMER_SETTLEMENT"),
  CUSTOMER_CREDIT_CHARGE("Credit charge", "CUSTOMER_CREDIT_CHARGE"),
  /** Synthetic carried-forward balance row, not a real transaction. */
  OPENING("Opening", "OPENING");

  private final String label;
  private final String sourceType;

  SalesMisTxnType(String label, String sourceType) {
    this.label = label;
    this.sourceType = sourceType;
  }

  public String label() {
    return label;
  }

  public String sourceType() {
    return sourceType;
  }

  /** Increases what the customer owes the shop. */
  public boolean increasesReceivable() {
    return this == SALE || this == CUSTOMER_CREDIT_CHARGE || this == OPENING;
  }

  /**
   * Counts toward the day's sales figure.
   *
   * <p>Returns qualify because they net the day down; receipts and credit charges do not — a
   * receipt collects money against an earlier sale rather than making a new one, so counting it
   * would book the same sale twice.
   */
  public boolean countsTowardSalesTotal() {
    return this == SALE || this == SALES_RETURN;
  }

  public static Optional<SalesMisTxnType> parse(String raw) {
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
