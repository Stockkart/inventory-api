package com.inventory.plugins.cafe;

import com.inventory.pluginengine.capabilities.FeatureFlags;
import com.inventory.pluginengine.capabilities.NavItemDef;
import com.inventory.pluginengine.capabilities.PurchaseSearchConfig;
import com.inventory.pluginengine.capabilities.SellSurface;
import com.inventory.pluginengine.capabilities.ShopUiCapabilities;
import com.inventory.pluginengine.capabilities.VerticalUiContributor;
import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CafeUiContributor implements VerticalUiContributor {

  @Override
  public String getVerticalId() {
    return "cafe";
  }

  @Override
  public ShopUiCapabilities contribute(VerticalSchema schema) {
    return ShopUiCapabilities.builder()
        .sellSurface(SellSurface.MENU_LIST)
        .navigation(
            List.of(
                NavItemDef.builder()
                    .id("product-registration")
                    .label("Ingredient Registration")
                    .path("/dashboard/product-registration")
                    .build(),
                NavItemDef.builder()
                    .id("manual-stock")
                    .label("Ingredient Search")
                    .path("/dashboard/manual-stock")
                    .build(),
                NavItemDef.builder()
                    .id("menu-admin")
                    .label("Menu")
                    .path("/dashboard/menu")
                    .build(),
                NavItemDef.builder()
                    .id("menu-sell")
                    .label("Sell")
                    .path("/dashboard/menu-sell")
                    .build()))
        .features(
            FeatureFlags.builder()
                .menuAdmin(true)
                .tokenOnReceipt(true)
                .manualStock(true)
                .customerReturn(false)
                .vendorReturn(false)
                .build())
        .purchaseSearch(
            PurchaseSearchConfig.builder()
                .fields(List.of("invoiceNo", "tokenNo", "customerPhone", "customerName"))
                .build())
        .build();
  }
}
