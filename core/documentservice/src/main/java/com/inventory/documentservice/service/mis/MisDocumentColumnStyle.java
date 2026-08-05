package com.inventory.documentservice.service.mis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Column alignment helpers for MIS PDF/Excel exports. */
final class MisDocumentColumnStyle {

  private static final Set<String> NUMERIC_HEADERS =
      Set.of(
          "cash",
          "online",
          "credit",
          "subtotal",
          "tax",
          "discount",
          "grand total",
          "cost",
          "profit",
          "margin %",
          "bill amount",
          "outstanding",
          "opening",
          "closing (period)",
          "current",
          "on hand",
          "threshold",
          "sell",
          "cost value",
          "sell value",
          "potential profit");

  private static final Set<String> DATE_HEADERS = Set.of("date");

  private MisDocumentColumnStyle() {}

  static boolean isNumeric(String header) {
    if (header == null || header.isBlank()) {
      return false;
    }
    return NUMERIC_HEADERS.contains(header.trim().toLowerCase(Locale.ROOT));
  }

  static boolean isDate(String header) {
    if (header == null || header.isBlank()) {
      return false;
    }
    return DATE_HEADERS.contains(header.trim().toLowerCase(Locale.ROOT));
  }

  static List<Boolean> flags(List<String> columns, boolean numeric) {
    List<String> cols = columns != null ? columns : List.of();
    List<Boolean> out = new ArrayList<>(cols.size());
    for (String col : cols) {
      out.add(numeric ? isNumeric(col) : isDate(col));
    }
    return out;
  }

  /** Chunk KPIs into fixed-width rows (pad with nulls) for a card-style grid. */
  static <T> List<List<T>> chunk(List<T> items, int size) {
    List<T> src = items != null ? items : List.of();
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < src.size(); i += size) {
      List<T> row = new ArrayList<>(src.subList(i, Math.min(i + size, src.size())));
      while (row.size() < size) {
        row.add(null);
      }
      out.add(row);
    }
    return out;
  }
}
