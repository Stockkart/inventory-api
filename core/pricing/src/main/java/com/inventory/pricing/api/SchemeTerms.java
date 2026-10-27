package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * A sale or purchase scheme as other modules see it. FIXED_UNITS: pay for {@code schemePayFor},
 * get {@code schemeFree} free (10+1). PERCENTAGE: {@code schemePercentage} off the price.
 */
public record SchemeTerms(
    String schemeType, Integer schemePayFor, Integer schemeFree, BigDecimal schemePercentage) {

  public static final String TYPE_FIXED_UNITS = "FIXED_UNITS";
  public static final String TYPE_PERCENTAGE = "PERCENTAGE";
}
