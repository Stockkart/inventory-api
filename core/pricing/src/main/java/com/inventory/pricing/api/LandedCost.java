package com.inventory.pricing.api;

import com.inventory.common.util.GstMath;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Landed cost per unit: what a unit really cost after the vendor's scheme and discount, and
 * optionally with the GST taken out. The one place this is worked out; pricing's own
 * {@code PricingUtils.computeEffectiveCostPrice} delegates here.
 */
public interface LandedCost {

  /**
   * Landed cost per unit before GST. FIXED_UNITS "8+2" means 10 units received for the price of 8,
   * so the per-unit cost is 8/10 of the entered cost. PERCENTAGE is a straight price reduction.
   * Both factors multiply, so order is irrelevant. When the cost was entered off a bill whose
   * rates include GST, the tax is taken out after the scheme and discount, at the lot's own rate:
   * {@code landed × 100 / (100 + sgst + cgst)}. Kept at 4 decimals: rounding per unit before
   * multiplying by quantity loses money on large lines.
   *
   * @return null when {@code costPrice} is null; never negative
   */
  static BigDecimal perUnit(
      BigDecimal costPrice,
      BigDecimal purchaseAdditionalDiscount,
      SchemeTerms purchaseScheme,
      boolean costPriceIncludesTax,
      String sgst,
      String cgst) {
    if (costPrice == null) {
      return null;
    }
    BigDecimal effective = costPrice;

    if (purchaseScheme != null) {
      if (SchemeTerms.TYPE_PERCENTAGE.equalsIgnoreCase(purchaseScheme.schemeType())) {
        BigDecimal pct = purchaseScheme.schemePercentage();
        if (pct != null && pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) < 0) {
          effective = effective.multiply(
              BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        }
      } else {
        Integer payFor = purchaseScheme.schemePayFor();
        Integer free = purchaseScheme.schemeFree();
        if (payFor != null && payFor > 0 && free != null && free >= 0) {
          effective = effective.multiply(BigDecimal.valueOf(payFor))
              .divide(BigDecimal.valueOf(payFor + (long) free), 6, RoundingMode.HALF_UP);
        }
      }
    }

    if (purchaseAdditionalDiscount != null && purchaseAdditionalDiscount.signum() != 0) {
      effective = effective.multiply(BigDecimal.ONE.subtract(
          purchaseAdditionalDiscount.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
    }

    if (costPriceIncludesTax) {
      BigDecimal gstRate = GstMath.parseGstRate(sgst).add(GstMath.parseGstRate(cgst));
      if (gstRate.signum() > 0) {
        effective = effective.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100).add(gstRate), 6, RoundingMode.HALF_UP);
      }
    }

    if (effective.signum() < 0) {
      effective = BigDecimal.ZERO;
    }
    return effective.setScale(4, RoundingMode.HALF_UP);
  }

  /** {@link #perUnit} with the GST left in: the cost after the scheme and discount only. */
  static BigDecimal perUnit(
      BigDecimal costPrice, BigDecimal purchaseAdditionalDiscount, SchemeTerms purchaseScheme) {
    return perUnit(costPrice, purchaseAdditionalDiscount, purchaseScheme, false, null, null);
  }
}
