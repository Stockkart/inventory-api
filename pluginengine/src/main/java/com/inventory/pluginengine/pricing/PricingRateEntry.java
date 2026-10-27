package com.inventory.pluginengine.pricing;

import java.math.BigDecimal;

/**
 * A named rate as the pricing policies see it. Everything after the price is null for a legacy,
 * hand-typed rate; the policies pass these through untouched.
 */
public record PricingRateEntry(
    String name,
    BigDecimal price,
    String base,
    BigDecimal baseValue,
    BigDecimal discountPercent,
    PricingSchemeEntry scheme,
    Boolean manual,
    String gstMode) {

  public PricingRateEntry(String name, BigDecimal price) {
    this(name, price, null, null, null, null, null, null);
  }
}
