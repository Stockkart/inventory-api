package com.inventory.pricing.utils;

import com.inventory.pricing.utils.constants.PricingConstants;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure sale-scheme and at-MRP maths, shared by checkout and rate pricing.
 *
 * <p>A PERCENTAGE scheme cuts the price; a FIXED_UNITS scheme cuts the billable quantity. The two
 * never mix: a FIXED_UNITS line keeps its price, and a PERCENTAGE line keeps its quantity.
 */
public final class SchemePriceUtils {

  private SchemePriceUtils() {}

  /**
   * The price after a PERCENTAGE scheme: {@code price × (1 − percentage/100)}, with the
   * {@code percentage/100} divide at 6 dp HALF_UP (as {@code PricingUtils.computeEffectiveCostPrice}
   * divides the purchase scheme) and the product left unrounded. Any other scheme, or a missing or
   * non-positive percentage, leaves {@code price} as it is.
   */
  public static BigDecimal percentagePrice(BigDecimal price, String schemeType, BigDecimal percentage) {
    if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equals(schemeType) && percentage != null
        && percentage.signum() > 0) {
      return price.multiply(
          BigDecimal.ONE.subtract(percentage.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    }
    return price;
  }

  /**
   * The quantity a FIXED_UNITS scheme bills: {@code qty × payFor / (payFor + free)} at 4 dp HALF_UP,
   * e.g. 11 on 10+1 bills 10. PERCENTAGE, no scheme, or an incomplete deal bills {@code qty}.
   */
  public static BigDecimal billableQuantity(BigDecimal qty, String schemeType, Integer payFor, Integer free) {
    if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equals(schemeType)) {
      return qty;
    }
    if (PricingConstants.SCHEME_TYPE_FIXED_UNITS.equals(schemeType) && payFor != null && payFor > 0
        && free != null && free >= 0) {
      BigDecimal pay = BigDecimal.valueOf(payFor);
      BigDecimal sum = pay.add(BigDecimal.valueOf(free));
      if (sum.compareTo(BigDecimal.ZERO) <= 0) {
        return qty;
      }
      return qty.multiply(pay).divide(sum, 4, RoundingMode.HALF_UP);
    }
    return qty;
  }

  /** True when the line's price equals its MRP (by value, so 115 equals 115.00). MRP is tax-inclusive. */
  public static boolean isAtMrp(BigDecimal linePrice, BigDecimal mrp) {
    if (linePrice == null || mrp == null) {
      return false;
    }
    return linePrice.compareTo(mrp) == 0;
  }
}
