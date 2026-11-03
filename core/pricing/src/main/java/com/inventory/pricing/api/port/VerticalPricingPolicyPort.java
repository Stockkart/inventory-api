package com.inventory.pricing.api.port;

import com.inventory.pricing.api.PricingDraft;

/**
 * A vertical's pricing rules (pharmacy, cafe, ...): validates pricing and returns it normalised.
 * Implemented in the product module, which holds the plugin registry. Each method throws a
 * validation exception when the rules reject the pricing.
 */
public interface VerticalPricingPolicyPort {

  /** @return the new pricing, normalised for its vertical */
  PricingDraft normalizeCreate(PricingDraft request);

  /**
   * @param existing the stored pricing, before the update
   * @param changes the update's changes; null fields are left as they are
   * @return the changes, normalised for the vertical
   */
  PricingDraft normalizeUpdate(PricingDraft existing, PricingDraft changes);

  /** @return a pricing record about to be stored, normalised for its vertical */
  PricingDraft normalizeStored(PricingDraft pricing);
}
