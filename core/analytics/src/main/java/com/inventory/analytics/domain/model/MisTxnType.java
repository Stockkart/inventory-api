package com.inventory.analytics.domain.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

  /**
   * Parses a comma-separated {@code txnTypes} query parameter.
   *
   * <p>Unrecognised tokens are dropped rather than failing the request. An empty result means "no
   * filter", so a request naming only unknown types returns everything — the same outcome as
   * before, when unknown strings simply never matched.
   */
  public static Set<MisTxnType> parseCsv(String csv) {
    if (csv == null || csv.isBlank()) {
      return Set.of();
    }
    return Arrays.stream(csv.split(","))
        .map(MisTxnType::parse)
        .flatMap(Optional::stream)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
