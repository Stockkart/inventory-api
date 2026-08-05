package com.inventory.analytics.mis.domain;

import java.util.Locale;

public enum MisVendorTxnType {
  PURCHASE("Purchase"),
  PAYMENT("Payment"),
  RETURN("Return"),
  CREDIT_CHARGE("Credit charge"),
  OPENING("Opening");

  private final String label;

  MisVendorTxnType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static MisVendorTxnType fromParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return MisVendorTxnType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
