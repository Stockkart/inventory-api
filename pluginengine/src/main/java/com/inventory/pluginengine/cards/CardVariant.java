package com.inventory.pluginengine.cards;

/**
 * Which billing-mode variant of a card layout applies. Mirrors the product module's {@code
 * BillingMode} without depending on it, so vertical plugins can build default layouts from the
 * plugin engine alone. {@code core/product} maps {@code BillingMode → CardVariant} in one place.
 */
public enum CardVariant {
  REGULAR,
  BASIC;

  /** Lenient parse used when reading persisted documents; unknown or null yields empty. */
  public static java.util.Optional<CardVariant> parse(String raw) {
    if (raw == null) {
      return java.util.Optional.empty();
    }
    String trimmed = raw.trim();
    for (CardVariant v : values()) {
      if (v.name().equalsIgnoreCase(trimmed)) {
        return java.util.Optional.of(v);
      }
    }
    return java.util.Optional.empty();
  }
}
