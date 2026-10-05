package com.inventory.product.cardlayout;

/**
 * Stable ids of the core card surfaces (configurable-product-card Req 2.1). Part of the API
 * contract: persisted in {@code shop_card_layouts} and used by the frontend; never rename.
 */
public final class CardSurfaceIds {

  /** The product search results page. */
  public static final String PRODUCT_SEARCH = "product-search";

  /** The Scan &amp; Sell search-result dropdown. */
  public static final String SCAN_SELL = "scan-sell";

  private CardSurfaceIds() {}
}
