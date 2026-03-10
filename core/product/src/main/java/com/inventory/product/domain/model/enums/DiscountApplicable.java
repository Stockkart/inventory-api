package com.inventory.product.domain.model.enums;

/**
 * What is applicable for this inventory item: discount, scheme, or both.
 */
public enum DiscountApplicable {
  /** Discount applicable */
  DISCOUNT,
  /** Scheme applicable */
  SCHEME,
  /** Both discount and scheme applicable */
  DISCOUNT_AND_SCHEME
}
