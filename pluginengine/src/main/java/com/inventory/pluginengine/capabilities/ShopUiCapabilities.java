package com.inventory.pluginengine.capabilities;

import java.util.List;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShopUiCapabilities {

  private SellSurface sellSurface;
  private List<NavItemDef> navigation;
  private FeatureFlags features;
  private PurchaseSearchConfig purchaseSearch;
}
