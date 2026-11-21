package com.inventory.ocr.api;

/**
 * Which invoice price columns the vision model should read.
 * Selected from shop type in the product layer — this module does not know {@code ShopType}.
 */
public enum InvoicePricingLayout {
  /** PTS → costPrice, PTR → priceToRetail, MRP → maximumRetailPrice. */
  WHOLESALER,
  /** Price/Unit → costPrice, MRP → maximumRetailPrice and priceToRetail (selling = MRP). */
  RETAILER;

  public static InvoicePricingLayout orDefault(InvoicePricingLayout layout) {
    return layout != null ? layout : WHOLESALER;
  }
}
