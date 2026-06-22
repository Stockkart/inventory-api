package com.inventory.pluginengine.cart;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CartBuildContext {

  private String shopId;
  private String verticalId;
  private String pluginVersion;
}
