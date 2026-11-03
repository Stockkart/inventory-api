package com.inventory.pricing.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.Builder;

/**
 * Changes to a lot's pricing, applied through {@link InventoryPricing#update}. A null field means
 * "leave it"; to clear one, name it in {@code clearFields}.
 *
 * @param verticalId the shop's vertical, for vertical pricing rules; may be null
 * @param maximumRetailPrice new MRP
 * @param costPrice new cost
 * @param priceToRetail new price to retailer
 * @param sellingPrice new selling price
 * @param rates new named rates
 * @param defaultRate new default rate
 * @param saleAdditionalDiscount new additional discount on sale
 * @param purchaseAdditionalDiscount new additional discount on purchase
 * @param purchaseScheme new purchase scheme
 * @param saleScheme new sale scheme
 * @param sgst new SGST rate
 * @param cgst new CGST rate
 * @param clearFields fields to clear, from {@link PricingClearFields#ALL}; may be null
 */
@Builder
public record PricingUpdate(
    String verticalId,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal priceToRetail,
    BigDecimal sellingPrice,
    List<RateLine> rates,
    String defaultRate,
    BigDecimal saleAdditionalDiscount,
    BigDecimal purchaseAdditionalDiscount,
    SchemeTerms purchaseScheme,
    SchemeTerms saleScheme,
    String sgst,
    String cgst,
    Set<String> clearFields) {}
