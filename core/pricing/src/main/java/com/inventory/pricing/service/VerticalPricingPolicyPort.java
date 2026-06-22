package com.inventory.pricing.service;

import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.rest.dto.request.CreatePricingRequest;
import com.inventory.pricing.rest.dto.request.UpdatePricingRequest;

/**
 * Applies vertical-specific pricing validation and normalization.
 * Implemented in the product module using plugin registry.
 */
public interface VerticalPricingPolicyPort {

  void validateAndNormalizeCreate(CreatePricingRequest request);

  void validateAndNormalizeUpdate(UpdatePricingRequest request, String verticalId, Pricing existing);

  void normalizeEntity(Pricing pricing, String verticalId);
}
