package com.inventory.plan.utils;

import com.inventory.plan.domain.model.AddOnBillingType;
import com.inventory.plan.domain.model.AddOnGrantType;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;

/** Catalogue rules for add-ons that follow from what the add-on grants. */
public final class AddOnRules {

  private AddOnRules() {}

  /**
   * Fills what the admin left out from the grant type: OCR credits are a one-time purchase and the
   * rest are annual; a feature add-on is bought once and grants 1, the others can be stacked. What
   * the admin did send is kept, and the validator rejects combinations that contradict these rules.
   */
  public static void applyDefaults(AddOnAdminRequest request, AddOnGrantType grantType) {
    if (grantType == null) {
      return;
    }
    if (request.getBillingType() == null) {
      request.setBillingType(grantType == AddOnGrantType.OCR_CREDITS ? AddOnBillingType.ONE_TIME : AddOnBillingType.ANNUAL);
    }
    if (request.getStackable() == null) {
      request.setStackable(grantType != AddOnGrantType.FEATURE);
    }
    if (grantType == AddOnGrantType.FEATURE && request.getGrantsQuantity() == null) {
      request.setGrantsQuantity(1);
    }
  }
}
