package com.inventory.pricing.utils;

import com.inventory.pricing.api.LandedCost;
import com.inventory.pricing.api.SchemeTerms;
import com.inventory.pricing.service.api.InventoryPricingImpl;
import com.inventory.pricing.rest.dto.response.PricingReadDto;
import com.inventory.pricing.domain.model.Pricing;
import com.inventory.pricing.domain.model.Rate;
import com.inventory.pricing.domain.model.Scheme;
import com.inventory.pricing.utils.constants.PricingConstants;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
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
    // one implementation, in the api record other modules read
    return dto == null ? null : InventoryPricingImpl.toLotPricing(dto).effectivePrice();
  }

  /**
   * Whether {@code rate} may be a batch's default rate. A rate built on the selling price may not:
   * the selling price would then be derived from a rate derived from it, so a default naming one
   * falls back to priceToRetail ({@code RateGenerationService}).
   */
  public static boolean canBeDefaultRate(Rate rate) {
    return rate != null && StringUtils.hasText(rate.getName())
        && !PricingConstants.RATE_BASE_SELLING_PRICE.equals(rate.getBase());
  }

  /**
   * The names a batch's default rate may take, in display order: the built-in options
   * (priceToRetail, maximumRetailPrice, costPrice), then each rate that {@link #canBeDefaultRate},
   * leaving out any stored rate with a built-in price's name ({@link SystemRateNames}: MRP, PTR,
   * Cost or a system key, any case), which a pick by that name would never bill.
   */
  public static List<String> defaultRateOptions(List<Rate> rates) {
    List<String> options = new ArrayList<>(List.of(
        PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL,
        PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE,
        PricingConstants.DEFAULT_RATE_COST_PRICE));
    if (rates != null) {
      for (Rate rate : rates) {
        if (canBeDefaultRate(rate) && !SystemRateNames.isReserved(rate.getName())
            && !options.contains(rate.getName())) {
          options.add(rate.getName());
        }
      }
    }
    return options;
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
    // the arithmetic lives in the api so other modules work landed cost out the same way
    return LandedCost.perUnit(
        costPrice, purchaseAdditionalDiscount, toTerms(purchaseScheme),
        costPriceIncludesTax, sgst, cgst);
  }

  private static SchemeTerms toTerms(Scheme s) {
    return s == null ? null
        : new SchemeTerms(s.getSchemeType(), s.getSchemePayFor(), s.getSchemeFree(),
            s.getSchemePercentage());
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
