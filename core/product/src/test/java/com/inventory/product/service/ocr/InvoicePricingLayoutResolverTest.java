package com.inventory.product.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.inventory.ocr.prompt.InvoicePricingLayout;
import com.inventory.product.domain.model.Shop;
import com.inventory.product.domain.model.enums.ShopType;
import org.junit.jupiter.api.Test;

class InvoicePricingLayoutResolverTest {

  @Test
  void retailerShopUsesRetailerLayout() {
    Shop shop = new Shop();
    shop.setShopType(ShopType.RETAILER);
    assertEquals(InvoicePricingLayout.RETAILER, InvoicePricingLayoutResolver.fromShop(shop));
  }

  @Test
  void wholesalerAndDistributorKeepWholesalerLayout() {
    Shop wholesaler = new Shop();
    wholesaler.setShopType(ShopType.WHOLESALER);
    Shop distributor = new Shop();
    distributor.setShopType(ShopType.DISTRIBUTOR);
    assertEquals(InvoicePricingLayout.WHOLESALER, InvoicePricingLayoutResolver.fromShop(wholesaler));
    assertEquals(InvoicePricingLayout.WHOLESALER, InvoicePricingLayoutResolver.fromShop(distributor));
  }

  @Test
  void missingShopDefaultsToWholesaler() {
    assertEquals(InvoicePricingLayout.WHOLESALER, InvoicePricingLayoutResolver.fromShop(null));
    assertEquals(InvoicePricingLayout.WHOLESALER, InvoicePricingLayoutResolver.fromShopType(null));
  }
}
