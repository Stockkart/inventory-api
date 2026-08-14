package com.inventory.product.service.ocr;

import com.inventory.ocr.prompt.InvoicePricingLayout;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;

/**
 * Maps shop type onto OCR price-column layout. OCR does not depend on {@link ShopType}.
 */
public final class InvoicePricingLayoutResolver {

  private InvoicePricingLayoutResolver() {}

  public static InvoicePricingLayout fromShop(Shop shop) {
    if (shop == null) {
      return InvoicePricingLayout.WHOLESALER;
    }
    return fromShopType(shop.getShopType());
  }

  public static InvoicePricingLayout fromShopType(ShopType shopType) {
    if (shopType == ShopType.RETAILER) {
      return InvoicePricingLayout.RETAILER;
    }
    return InvoicePricingLayout.WHOLESALER;
  }
}
