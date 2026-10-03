package com.inventory.pricing.utils;

import com.inventory.common.util.GstMath;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Utility methods for pricing-related computations.
 */
public final class PricingUtils {

  private PricingUtils() {}

  /**
   * Resolve effective selling price from PricingReadDto using defaultRate: maximumRetailPrice, priceToRetail, costPrice, or rate name.
   */
  public static BigDecimal resolveEffectivePriceFromReadDto(PricingReadDto dto) {
    if (dto == null) return null;
    if (dto.getSellingPrice() != null) return dto.getSellingPrice();
    if (!StringUtils.hasText(dto.getDefaultRate())) return dto.getPriceToRetail();
    String dr = dto.getDefaultRate().trim();
    if (PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.equalsIgnoreCase(dr)) {
      return dto.getMaximumRetailPrice() != null ? dto.getMaximumRetailPrice() : dto.getPriceToRetail();
    }
    if (PricingConstants.DEFAULT_RATE_COST_PRICE.equalsIgnoreCase(dr)) {
      return dto.getCostPrice() != null ? dto.getCostPrice() : dto.getPriceToRetail();
    }
    if (PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.equalsIgnoreCase(dr)) {
      return dto.getPriceToRetail();
    }
    List<RateDto> rates = dto.getRates();
    if (rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> dr.equals(r.getName()))
          .map(RateDto::getPrice)
          .findFirst()
          .orElse(dto.getPriceToRetail());
    }
    return dto.getPriceToRetail();
  }

  /**
   * Resolve effective selling price from defaultRate: maximumRetailPrice, priceToRetail, costPrice, or rate name.
   */
  public static BigDecimal resolveEffectiveSellingPrice(Pricing p) {
    if (p == null) {
      return null;
    }
    if (!StringUtils.hasText(p.getDefaultRate())) {
      return p.getPriceToRetail();
    }
    String dr = p.getDefaultRate().trim();
    if (PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.equalsIgnoreCase(dr)) {
      return p.getMaximumRetailPrice() != null ? p.getMaximumRetailPrice() : p.getPriceToRetail();
    }
    if (PricingConstants.DEFAULT_RATE_COST_PRICE.equalsIgnoreCase(dr)) {
      return p.getCostPrice() != null ? p.getCostPrice() : p.getPriceToRetail();
    }
    if (PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.equalsIgnoreCase(dr)) {
      return p.getPriceToRetail();
    }
    if (p.getRates() != null) {
      return p.getRates().stream()
          .filter(r -> dr.equals(r.getName()))
          .map(Rate::getPrice)
          .findFirst()
          .orElse(p.getPriceToRetail());
    }
    return p.getPriceToRetail();
  }

  /**
   * Landed cost per unit after the vendor's purchase scheme and additional discount.
   * FIXED_UNITS "8+2" means 10 units received for the price of 8, so per-unit cost is 8/10 of the
   * entered cost. PERCENTAGE is a straight price reduction. Both factors multiply, so order is
   * irrelevant. Kept at 4 decimals: rounding per unit before multiplying by quantity loses money
   * on large lines.
   */
  public static BigDecimal computeEffectiveCostPrice(
      BigDecimal costPrice, BigDecimal purchaseAdditionalDiscount, Scheme purchaseScheme) {
    return computeEffectiveCostPrice(
        costPrice, purchaseAdditionalDiscount, purchaseScheme, false, null, null);
  }

  /**
   * Landed cost per unit before GST. When the cost was entered off a bill whose rates include GST,
   * the tax is taken out after the scheme and discount, at the lot's own rate:
   * {@code landed × 100 / (100 + sgst + cgst)}. That is the per-unit form of what
   * {@code PurchaseTaxBasisResolver} does to the bill line, so 36 × 99 less 24% at 5% inclusive
   * lands at 71.6571 a unit and 2579.66 for the line, matching the taxable value on the bill.
   */
  public static BigDecimal computeEffectiveCostPrice(
      BigDecimal costPrice, BigDecimal purchaseAdditionalDiscount, Scheme purchaseScheme,
      boolean costPriceIncludesTax, String sgst, String cgst) {
    if (costPrice == null) {
      return null;
    }
    BigDecimal effective = costPrice;

    if (purchaseScheme != null) {
      if (PricingConstants.SCHEME_TYPE_PERCENTAGE.equalsIgnoreCase(purchaseScheme.getSchemeType())) {
        BigDecimal pct = purchaseScheme.getSchemePercentage();
        if (pct != null && pct.signum() > 0 && pct.compareTo(BigDecimal.valueOf(100)) < 0) {
          effective = effective.multiply(
              BigDecimal.ONE.subtract(pct.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)));
        }
      } else {
        Integer payFor = purchaseScheme.getSchemePayFor();
        Integer free = purchaseScheme.getSchemeFree();
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

  /** Landed cost before GST for a pricing record; the entered cost when nothing reduces it. */
  public static BigDecimal computeEffectiveCostPrice(Pricing p) {
    if (p == null) {
      return null;
    }
    return computeEffectiveCostPrice(
        p.getCostPrice(), p.getPurchaseAdditionalDiscount(), p.getPurchaseScheme(),
        Boolean.TRUE.equals(p.getCostPriceIncludesTax()), p.getSgst(), p.getCgst());
  }

  /**
   * Parse BigDecimal from a document value. Handles Number, BigDecimal, and string representations.
   */
  public static BigDecimal getBigDecimalFromObject(Object v) {
    if (v == null) return null;
    if (v instanceof BigDecimal) return (BigDecimal) v;
    if (v instanceof Number) return BigDecimal.valueOf(((Number) v).doubleValue());
    try {
      return new BigDecimal(v.toString());
    } catch (Exception e) {
      return null;
    }
  }
}
