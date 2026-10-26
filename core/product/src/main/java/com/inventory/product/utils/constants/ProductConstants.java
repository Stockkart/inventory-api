package com.inventory.product.utils.constants;

/**
 * Constants for product, inventory, and checkout logic.
 */
public final class ProductConstants {

  private ProductConstants() {}

  /** Maximum quantity per item in a sale. */
  public static final int MAX_QUANTITY_PER_ITEM = 1000;

  /** Maximum number of items per sale/cart. */
  public static final int MAX_ITEMS_PER_SALE = 100;

  /** Default base unit when not specified. */
  public static final String DEFAULT_BASE_UNIT = "UNIT";

  /**
   * Lot fields an update may clear by naming them in {@code clearFields} (a null field means
   * "keep it"). Pricing fields are in {@code PricingConstants.CLEARABLE_FIELDS}. The name cannot
   * be cleared.
   */
  public static final java.util.Set<String> CLEARABLE_INVENTORY_FIELDS = java.util.Set.of(
      "barcode", "description", "companyName", "location", "hsn", "discountApplicable",
      "thresholdCount");
}
