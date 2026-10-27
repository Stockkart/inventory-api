package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * One shop rate rule as pricing reads it: a name, a base ({@link #BASE_SELLING_PRICE} …), an
 * optional discount and an optional scheme.
 */
public record ShopRateRule(String name, String base, BigDecimal discountPercent, SchemeTerms scheme) {

  public static final String BASE_SELLING_PRICE = "sellingPrice";
  public static final String BASE_PRICE_TO_RETAIL = "priceToRetail";
  public static final String BASE_MAXIMUM_RETAIL_PRICE = "maximumRetailPrice";
}
