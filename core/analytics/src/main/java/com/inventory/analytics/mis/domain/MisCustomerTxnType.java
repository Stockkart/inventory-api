package com.inventory.analytics.mis.domain;

import java.util.Locale;

public enum MisCustomerTxnType {
  SALE("Sale"),
  COLLECTION("Collection"),
  REFUND("Refund"),
  CREDIT_CHARGE("Credit charge"),
  OPENING("Opening");

  private final String label;

  MisCustomerTxnType(String label) {
    this.label = label;
  }

  public String label() {
    return label;
  }

  public static MisCustomerTxnType fromParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return MisCustomerTxnType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }
}
