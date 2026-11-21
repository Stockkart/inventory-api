package com.inventory.product.search;

import com.inventory.product.labels.PrintableField;
import java.time.Instant;
import java.util.List;

/**
 * A search request after validation: every field resolved to its catalog entry, every value parsed
 * to its real type, defaults filled in. The planner works from this, never from the raw request.
 */
public record ValidatedSearch(
    TextPattern text,
    List<Clause> clauses,
    MatchMode match,
    List<PrintableField> facets,
    Sort sort,
    int page,
    int size,
    boolean includeZeroStock,
    String surface) {

  public ValidatedSearch {
    clauses = clauses == null ? List.of() : List.copyOf(clauses);
    facets = facets == null ? List.of() : List.copyOf(facets);
    match = match == null ? MatchMode.ALL : match;
    surface = surface == null || surface.isBlank() ? "product-search" : surface.trim();
  }

  public boolean hasText() {
    return text != null;
  }

  public int skip() {
    return page * size;
  }

  /** One validated filter group. Exactly one of the value shapes is populated per operator. */
  public record Clause(
      PrintableField field,
      FilterOp op,
      /** {@code IN}: the raw values; {@code MATCHES}: unused (see pattern). */
      List<String> values,
      /** {@code MATCHES}: the compiled pattern. */
      TextPattern pattern,
      /** {@code BETWEEN} on numbers. */
      Double fromNumber,
      Double toNumber,
      /** {@code BETWEEN} / {@code WITHIN_DAYS} on dates. */
      Instant fromDate,
      Instant toDate) {

    public Clause {
      values = values == null ? List.of() : List.copyOf(values);
    }

    public SearchSpec spec() {
      return field.searchSpec();
    }

    public String path() {
      return spec().path();
    }
  }

  /** A validated sort key. */
  public record Sort(PrintableField field, boolean ascending) {
    public String path() {
      return field.searchSpec().path();
    }

    public String wire() {
      return field.fieldKey() + ":" + (ascending ? "asc" : "desc");
    }
  }
}
