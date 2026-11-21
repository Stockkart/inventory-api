package com.inventory.pricing.utils.constants;

public final class PricingMetricsConstants {

  private PricingMetricsConstants() {}

  public static final String MODULE = "pricing";

  /** A rate rule that produced no rate for a batch; tag {@code reason} (e.g. BELOW_COST). */
  public static final String RATE_RULE_SKIPPED_TOTAL = "inventory_pricing_rate_rule_skipped_total";

  /** A defaultRate that named a rate no longer generated, reset to priceToRetail. */
  public static final String RATE_DEFAULT_FALLBACK_TOTAL = "inventory_pricing_rate_default_fallback_total";
}
