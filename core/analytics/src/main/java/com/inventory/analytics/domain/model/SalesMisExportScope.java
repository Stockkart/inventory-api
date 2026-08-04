package com.inventory.analytics.domain.model;

import java.util.Locale;

/**
 * Which table of the Sales MIS an export contains.
 *
 * <p>The screen shows two: a day-wise trading summary and the transaction ledger beneath it. A
 * single spreadsheet or PDF holds one table, so the caller says which one it wants.
 *
 * <p>Arrives as a query parameter, so parsing is lenient: blank or unrecognised input falls back to
 * {@link #DAILY} rather than rejecting the request.
 */
public enum SalesMisExportScope {
  /** Day-wise summary: one row per trading day, with the month-to-date running total. */
  DAILY,
  /** The full transaction ledger, one row per sale / receipt / return / charge. */
  TRANSACTIONS;

  public static SalesMisExportScope from(String raw) {
    if (raw == null || raw.isBlank()) {
      return DAILY;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return DAILY;
    }
  }

  /** Filename infix, so a downloaded file says which table it holds. */
  public String filenameInfix() {
    return this == DAILY ? "daily-" : "";
  }
}
