package com.inventory.pricing.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * Pricing as handed to a vertical's pricing rules: a new record, an update's changes, or a
 * stored record. See {@link com.inventory.pricing.api.port.VerticalPricingPolicyPort}.
 *
 * @param shopId shop; null on an update's changes
 * @param verticalId the shop's vertical, which picks the rules
 * @param maximumRetailPrice MRP
 * @param costPrice cost
 * @param priceToRetail price to retailer
 * @param sellingPrice selling price
 * @param rates named rates; may be null
 * @param defaultRate which price the lot sells at by default
 * @param saleAdditionalDiscount additional discount on sale
 * @param purchaseAdditionalDiscount additional discount on purchase
 * @param purchaseScheme purchase scheme; null when there is none
 * @param saleScheme sale scheme; null when there is none
 * @param sgst SGST rate as text
 * @param cgst CGST rate as text
 */
public record PricingDraft(
    String shopId,
    String verticalId,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal priceToRetail,
    BigDecimal sellingPrice,
    List<RateView> rates,
    String defaultRate,
    BigDecimal saleAdditionalDiscount,
    BigDecimal purchaseAdditionalDiscount,
    SchemeTerms purchaseScheme,
    SchemeTerms saleScheme,
    String sgst,
    String cgst) {}
