package com.inventory.pricing.utils;

import com.inventory.pricing.api.SchemePricing;
import java.math.BigDecimal;

/** Pricing's name for {@link SchemePricing}: the sale-scheme and at-MRP maths. */
public final class SchemePriceUtils {

  private SchemePriceUtils() {}

  /** See {@link SchemePricing#percentagePrice}. */
  public static BigDecimal percentagePrice(BigDecimal price, String schemeType, BigDecimal percentage) {
    return SchemePricing.percentagePrice(price, schemeType, percentage);
  }

  /** See {@link SchemePricing#billableQuantity}. */
  public static BigDecimal billableQuantity(BigDecimal qty, String schemeType, Integer payFor, Integer free) {
    return SchemePricing.billableQuantity(qty, schemeType, payFor, free);
  }

  /** See {@link SchemePricing#isAtMrp}. */
  public static boolean isAtMrp(BigDecimal linePrice, BigDecimal mrp) {
    return SchemePricing.isAtMrp(linePrice, mrp);
  }
}
