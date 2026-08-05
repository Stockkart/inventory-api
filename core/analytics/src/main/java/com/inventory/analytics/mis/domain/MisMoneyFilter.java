package com.inventory.analytics.mis.domain;

import java.util.Locale;

/** Money-column filter for party money MIS. */
public enum MisMoneyFilter {
  ALL,
  HAS_CASH,
  HAS_ONLINE,
  HAS_CREDIT,
  FULLY_PAID,
  MIXED;

  public static MisMoneyFilter fromParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return ALL;
    }
    try {
      return MisMoneyFilter.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return ALL;
    }
  }
}
