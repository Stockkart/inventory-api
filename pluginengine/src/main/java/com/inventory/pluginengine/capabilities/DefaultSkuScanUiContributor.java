package com.inventory.pluginengine.capabilities;

import com.inventory.pluginengine.schema.VerticalSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/** Default capabilities for SKU scan-sell verticals (medical, sports, …). */
@Component
public class DefaultSkuScanUiContributor implements VerticalUiContributor {

  @Override
  public String getVerticalId() {
    return "default";
  }

  @Override
  public ShopUiCapabilities contribute(VerticalSchema schema) {
    return ShopUiCapabilities.builder()
        .sellSurface(SellSurface.SKU_SCAN)
        .navigation(
            List.of(
                NavItemDef.builder()
                    .id("scan-sell")
                    .label("Scan & Sell")
                    .path("/dashboard/scan-sell")
                    .build(),
                NavItemDef.builder()
                    .id("product-registration")
                    .label("Products")
                    .path("/dashboard/product-registration")
                    .build()))
        .features(FeatureFlags.builder().build())
        .purchaseSearch(
            PurchaseSearchConfig.builder()
                .fields(List.of("invoiceNo", "customerPhone", "customerName"))
                .build())
        .build();
  }
}
