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
}
