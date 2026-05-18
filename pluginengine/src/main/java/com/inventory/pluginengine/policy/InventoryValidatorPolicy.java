package com.inventory.pluginengine.policy;

import com.inventory.pluginengine.profile.BusinessProfile;

/**
 * Validates inventory create requests according to business profile rules.
 */
public interface InventoryValidatorPolicy {

  void validateCreate(InventoryCreateValidationInput input, BusinessProfile profile);
}
