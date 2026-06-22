package com.inventory.pluginengine.pricing;

/**
 * Vertical-specific pricing validation and normalization.
 * Retail verticals use {@link com.inventory.pluginengine.pricing.defaultprovider.DefaultRetailPricingPolicy};
 * cafe uses a minimal cost + optional selling price model.
 */
public interface VerticalPricingPolicy {

  void validateCreate(PricingPolicyContext context);

  void validateUpdate(PricingPolicyContext existing, PricingPolicyContext patch);

  PricingPolicyContext normalizeOnCreate(PricingPolicyContext context);

  PricingPolicyContext normalizeOnUpdate(PricingPolicyContext existing, PricingPolicyContext patch);
}
