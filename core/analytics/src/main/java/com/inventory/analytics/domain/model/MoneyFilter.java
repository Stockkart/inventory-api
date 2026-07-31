package com.inventory.analytics.domain.model;

import java.util.Locale;

/**
 * Row filter for the money columns of a MIS report.
 *
 * <p>Arrives as a query parameter, so parsing is lenient: blank or unrecognised input falls back to
 * {@link #ALL} rather than rejecting the request, matching the previous string behaviour where an
 * unknown filter simply matched everything.
 */
public enum MoneyFilter {
  /** No filtering. */
  ALL,
  HAS_CASH,
  HAS_ONLINE,
  HAS_CREDIT,
  /** Nothing left on credit, and the row carries an amount. */
  FULLY_PAID,
  /** More than one of cash / online / credit is non-zero. */
  MIXED;

  public static MoneyFilter from(String raw) {
    if (raw == null || raw.isBlank()) {
      return ALL;
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return ALL;
    }
  }
}
