package com.inventory.product.service.vertical;

import com.inventory.pluginengine.PluginRegistry;
import com.inventory.pluginengine.VerticalPlugin;
import com.inventory.pluginengine.pricing.VerticalPricingPolicy;
import com.inventory.pluginengine.pricing.defaultprovider.DefaultRetailPricingPolicy;
import org.springframework.stereotype.Component;

@Component
public class PricingPolicyResolver {

  private final PluginRegistry pluginRegistry;
  private final DefaultRetailPricingPolicy defaultRetailPricingPolicy;

  public PricingPolicyResolver(
      PluginRegistry pluginRegistry, DefaultRetailPricingPolicy defaultRetailPricingPolicy) {
    this.pluginRegistry = pluginRegistry;
    this.defaultRetailPricingPolicy = defaultRetailPricingPolicy;
  }

  public VerticalPricingPolicy resolve(String verticalId) {
    if (verticalId == null || verticalId.isBlank()) {
      return defaultRetailPricingPolicy;
    }
    return pluginRegistry
        .find(verticalId)
        .flatMap(VerticalPlugin::getPricingPolicy)
        .orElse(defaultRetailPricingPolicy);
  }
}
