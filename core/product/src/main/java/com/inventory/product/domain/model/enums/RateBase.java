package com.inventory.product.domain.model.enums;

import com.inventory.pricing.api.ShopRateRule;

/** The price a rate rule starts from. {@link #value()} is the wire and pricing name. */
public enum RateBase {
  SELLING_PRICE(ShopRateRule.BASE_SELLING_PRICE),
  PRICE_TO_RETAIL(ShopRateRule.BASE_PRICE_TO_RETAIL),
  MAXIMUM_RETAIL_PRICE(ShopRateRule.BASE_MAXIMUM_RETAIL_PRICE);

  private final String value;

  RateBase(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  /** The base for a wire value, or null when it names none. */
  public static RateBase fromValue(String value) {
    for (RateBase base : values()) {
      if (base.value.equals(value)) {
        return base;
      }
    }
    return null;
  }
}
