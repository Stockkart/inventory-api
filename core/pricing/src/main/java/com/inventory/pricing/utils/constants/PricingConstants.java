package com.inventory.pricing.utils.constants;

/**
 * Constants for pricing logic.
 */
public final class PricingConstants {

  private PricingConstants() {}

  /** Default rate when not specified: use priceToRetail. */
  public static final String DEFAULT_RATE_PRICE_TO_RETAIL = "priceToRetail";

  /** Rate key for maximum retail price. */
  public static final String DEFAULT_RATE_MAXIMUM_RETAIL_PRICE = "maximumRetailPrice";

  /** Rate key for cost price. */
  public static final String DEFAULT_RATE_COST_PRICE = "costPrice";
}
