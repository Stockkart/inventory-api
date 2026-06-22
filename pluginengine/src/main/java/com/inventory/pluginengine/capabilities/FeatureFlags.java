package com.inventory.pluginengine.capabilities;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeatureFlags {

  @Builder.Default private boolean menuAdmin = false;
  @Builder.Default private boolean tokenOnReceipt = false;
  @Builder.Default private boolean manualStock = false;
  @Builder.Default private boolean customerReturn = true;
  @Builder.Default private boolean vendorReturn = true;
  /** When true, ingredient registration uses cost + optional sell price only (no PTR/MRP/rates). */
  @Builder.Default private boolean simplePricing = false;
}
