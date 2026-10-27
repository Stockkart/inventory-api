package com.inventory.pricing.api;

import java.util.Set;

/**
 * Pricing fields an update may clear by naming them in {@link PricingUpdate#clearFields()}.
 * Sending null for a field means "leave it", so clearing needs the name.
 */
public interface PricingClearFields {

  String SALE_ADDITIONAL_DISCOUNT = "saleAdditionalDiscount";
  String PURCHASE_ADDITIONAL_DISCOUNT = "purchaseAdditionalDiscount";
  String SALE_SCHEME = "saleScheme";
  String PURCHASE_SCHEME = "purchaseScheme";

  /** Every field that may be cleared. */
  Set<String> ALL =
      Set.of(SALE_ADDITIONAL_DISCOUNT, PURCHASE_ADDITIONAL_DISCOUNT, SALE_SCHEME, PURCHASE_SCHEME);
}
