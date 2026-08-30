package com.inventory.pricing.utils;

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

    if (effective.signum() < 0) {
      effective = BigDecimal.ZERO;
    }
    return effective.setScale(4, RoundingMode.HALF_UP);
  }

  /** Landed cost for a pricing record, falling back to the entered cost when nothing reduces it. */
  public static BigDecimal computeEffectiveCostPrice(Pricing p) {
    if (p == null) {
      return null;
    }
    return computeEffectiveCostPrice(
        p.getCostPrice(), p.getPurchaseAdditionalDiscount(), p.getPurchaseScheme());
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
