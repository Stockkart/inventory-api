package com.inventory.pricing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * A lot's prices as the inventory screens use them, read through {@link InventoryPricing}.
 *
 * @param maximumRetailPrice MRP
 * @param costPrice cost as entered off the vendor's bill
 * @param effectiveCostPrice stored landed cost; see {@link #resolveEffectiveCostPrice()}
 * @param priceToRetail price to retailer
 * @param rates named rates
 * @param defaultRate which price the lot sells at by default
 * @param sellingPrice selling price
 * @param saleAdditionalDiscount additional discount on sale, percent
 * @param purchaseAdditionalDiscount additional discount on purchase, percent
 * @param purchaseScheme the vendor's scheme on purchase; may be null
 * @param saleScheme the scheme offered on sale; may be null
 * @param sgst SGST rate as text
 * @param cgst CGST rate as text
 * @param costPriceIncludesTax true when the entered cost includes GST
 */
public record LotPricing(
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal effectiveCostPrice,
    BigDecimal priceToRetail,
    List<RateLine> rates,
    String defaultRate,
    BigDecimal sellingPrice,
    BigDecimal saleAdditionalDiscount,
    BigDecimal purchaseAdditionalDiscount,
    SchemeTerms purchaseScheme,
    SchemeTerms saleScheme,
    String sgst,
    String cgst,
    Boolean costPriceIncludesTax) {

  /** True when no price, rate, discount or tax is set. */
  public boolean isEmpty() {
    return maximumRetailPrice == null && costPrice == null && priceToRetail == null
        && (rates == null || rates.isEmpty())
        && saleAdditionalDiscount == null && sgst == null && cgst == null;
  }

  /**
   * The price the lot sells at: the selling price when set, else the price {@code defaultRate}
   * names (MRP, PTR, cost or a named rate), else the price to retailer.
   */
  public BigDecimal effectivePrice() {
    if (sellingPrice != null) return sellingPrice;
    if (defaultRate == null || defaultRate.isBlank()) return priceToRetail;
    String dr = defaultRate.trim();
    if (SystemRates.MAXIMUM_RETAIL_PRICE.equalsIgnoreCase(dr)) {
      return maximumRetailPrice != null ? maximumRetailPrice : priceToRetail;
    }
    if (SystemRates.COST_PRICE.equalsIgnoreCase(dr)) {
      return costPrice != null ? costPrice : priceToRetail;
    }
    if (SystemRates.PRICE_TO_RETAIL.equalsIgnoreCase(dr)) {
      return priceToRetail;
    }
    if (rates != null && !rates.isEmpty()) {
      return rates.stream()
          .filter(r -> dr.equals(r.name()))
          .map(RateLine::price)
          .findFirst()
          .orElse(priceToRetail);
    }
    return priceToRetail;
  }

  /**
   * Landed cost per unit: the cost after the vendor's purchase scheme and additional discount, and
   * before GST -- taken out here when the cost was entered off a tax-inclusive bill.
   *
   * <p>Derived from those fields whenever they are there to derive from, and only then falling
   * back to the stored {@code effectiveCostPrice}. The stored figure is a cache of this same
   * arithmetic; deriving makes the inputs the answer and the cache the fallback, so it cannot go
   * stale. The fallback still matters: a record with no cost price has nothing to derive from,
   * and one written before the field existed has nothing stored.
   */
  public BigDecimal resolveEffectiveCostPrice() {
    BigDecimal derived = LandedCost.perUnit(
        costPrice,
        purchaseAdditionalDiscount,
        purchaseScheme,
        Boolean.TRUE.equals(costPriceIncludesTax),
        sgst,
        cgst);
    return derived != null ? derived : effectiveCostPrice;
  }
}
