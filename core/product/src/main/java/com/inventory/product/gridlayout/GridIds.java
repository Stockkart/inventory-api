package com.inventory.product.gridlayout;

/**
 * Stable ids of the configurable data grids. Part of the API contract: persisted in {@code
 * shop_grid_layouts} and used by the frontend; never rename. Each id has one {@link GridCatalog}.
 */
public final class GridIds {

  /** The product entry (stock-in) grid view. */
  public static final String PRODUCT_ENTRY = "product-entry";

  private GridIds() {}
}
