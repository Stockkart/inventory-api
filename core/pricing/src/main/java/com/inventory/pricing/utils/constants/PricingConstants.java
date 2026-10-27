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

  /** Scheme type where the discount is expressed as a percentage rather than free units. */
  public static final String SCHEME_TYPE_PERCENTAGE = "PERCENTAGE";

  /** Scheme type where the deal is free units, e.g. 10+1: pay for 10, get 11. */
  public static final String SCHEME_TYPE_FIXED_UNITS = "FIXED_UNITS";

  /** Rate rule base: the selling price. */
  public static final String RATE_BASE_SELLING_PRICE = "sellingPrice";

  /** Rate rule base: the price to retail. */
  public static final String RATE_BASE_PRICE_TO_RETAIL = "priceToRetail";

  /** Rate rule base: the maximum retail price. */
  public static final String RATE_BASE_MAXIMUM_RETAIL_PRICE = "maximumRetailPrice";

  /** The rate's price already contains GST. */
  public static final String GST_MODE_INCLUSIVE = "INCLUSIVE";

  /** GST is added on top of the rate's price. */
  public static final String GST_MODE_EXCLUSIVE = "EXCLUSIVE";

  /**
   * Pricing fields an update may clear by naming them in {@code clearFields}. Sending null for a
   * field means "leave it", so clearing needs the name.
   */
  public static final String CLEAR_SALE_ADDITIONAL_DISCOUNT = "saleAdditionalDiscount";
  public static final String CLEAR_PURCHASE_ADDITIONAL_DISCOUNT = "purchaseAdditionalDiscount";
  public static final String CLEAR_SALE_SCHEME = "saleScheme";
  public static final String CLEAR_PURCHASE_SCHEME = "purchaseScheme";

  public static final java.util.Set<String> CLEARABLE_FIELDS = java.util.Set.of(
      CLEAR_SALE_ADDITIONAL_DISCOUNT,
      CLEAR_PURCHASE_ADDITIONAL_DISCOUNT,
      CLEAR_SALE_SCHEME,
      CLEAR_PURCHASE_SCHEME);
}
