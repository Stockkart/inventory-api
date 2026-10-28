package com.inventory.pricing.utils.constants;

import com.inventory.pricing.api.PricingClearFields;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.api.ShopRateRule;
import com.inventory.pricing.api.SystemRates;

/**
 * Constants for pricing logic.
 */
public final class PricingConstants {

  private PricingConstants() {}

  /** Default rate when not specified: use priceToRetail. */
  public static final String DEFAULT_RATE_PRICE_TO_RETAIL = SystemRates.PRICE_TO_RETAIL;

  /** Rate key for maximum retail price. */
  public static final String DEFAULT_RATE_MAXIMUM_RETAIL_PRICE = SystemRates.MAXIMUM_RETAIL_PRICE;

  /** Rate key for cost price. */
  public static final String DEFAULT_RATE_COST_PRICE = SystemRates.COST_PRICE;

  /** Scheme type where the discount is expressed as a percentage rather than free units. */
  public static final String SCHEME_TYPE_PERCENTAGE = SchemeTerms.TYPE_PERCENTAGE;

  /** Scheme type where the deal is free units, e.g. 10+1: pay for 10, get 11. */
  public static final String SCHEME_TYPE_FIXED_UNITS = SchemeTerms.TYPE_FIXED_UNITS;

  /** Rate rule base: the selling price. */
  public static final String RATE_BASE_SELLING_PRICE = ShopRateRule.BASE_SELLING_PRICE;

  /** Rate rule base: the price to retail. */
  public static final String RATE_BASE_PRICE_TO_RETAIL = ShopRateRule.BASE_PRICE_TO_RETAIL;

  /** Rate rule base: the maximum retail price. */
  public static final String RATE_BASE_MAXIMUM_RETAIL_PRICE = ShopRateRule.BASE_MAXIMUM_RETAIL_PRICE;

  /** The rate's price already contains GST. */
  public static final String GST_MODE_INCLUSIVE = "INCLUSIVE";

  /** GST is added on top of the rate's price. */
  public static final String GST_MODE_EXCLUSIVE = "EXCLUSIVE";

  /**
   * Pricing fields an update may clear by naming them in {@code clearFields}. Sending null for a
   * field means "leave it", so clearing needs the name.
   */
  public static final String CLEAR_SALE_ADDITIONAL_DISCOUNT =
      PricingClearFields.SALE_ADDITIONAL_DISCOUNT;
  public static final String CLEAR_PURCHASE_ADDITIONAL_DISCOUNT =
      PricingClearFields.PURCHASE_ADDITIONAL_DISCOUNT;
  public static final String CLEAR_SALE_SCHEME = PricingClearFields.SALE_SCHEME;
  public static final String CLEAR_PURCHASE_SCHEME = PricingClearFields.PURCHASE_SCHEME;

  public static final java.util.Set<String> CLEARABLE_FIELDS = PricingClearFields.ALL;
}
