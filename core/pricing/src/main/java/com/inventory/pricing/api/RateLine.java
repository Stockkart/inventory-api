package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * One named rate on a lot, as screens show and send it: the price plus the terms it was worked
 * from. The same JSON shape the inventory and stock-entry endpoints have always used.
 *
 * @param name rate name, e.g. {@code wholesale}
 * @param price the price this rate sells at
 * @param base what the rate is worked from, e.g. {@code priceToRetail}; null for a typed price
 * @param baseValue the base price it was worked from
 * @param discountPercent discount off the base
 * @param scheme scheme offered at this rate; may be null
 * @param manual true when the price was typed rather than generated from a shop rule
 * @param gstMode {@code INCLUSIVE} or {@code EXCLUSIVE}
 * @param termsText read-only, built by pricing from the terms above, e.g.
 *     "PTR 100.00 − 8% · 10+1"; null for legacy rates
 */
public record RateLine(
    String name,
    BigDecimal price,
    String base,
    BigDecimal baseValue,
    BigDecimal discountPercent,
    SchemeTerms scheme,
    Boolean manual,
    String gstMode,
    String termsText) {

  /** A rate with only a name and a price, as a hand-typed rate arrives. */
  public static RateLine of(String name, BigDecimal price) {
    return new RateLine(name, price, null, null, null, null, null, null, null);
  }

  /** This rate as {@link RateTerms}. */
  public RateTerms toTerms() {
    return new RateTerms(name, price, base, baseValue, discountPercent, scheme);
  }
}
