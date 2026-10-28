package com.inventory.pricing.api;

import java.math.BigDecimal;
import java.util.List;
import lombok.Builder;

/**
 * A new lot's pricing, created through {@link InventoryPricing#create}.
 *
 * @param shopId shop; required
 * @param verticalId the shop's vertical, for vertical pricing rules; may be null
 * @param maximumRetailPrice MRP
 * @param costPrice cost as entered off the vendor's bill
 * @param priceToRetail price to retailer
 * @param sellingPrice selling price
 * @param rates named rates; may be null
 * @param defaultRate which price the lot sells at by default; null lets pricing choose
 * @param saleAdditionalDiscount additional discount on sale, percent
 * @param purchaseAdditionalDiscount additional discount on purchase, percent
 * @param purchaseScheme the vendor's scheme; may be null
 * @param saleScheme the scheme offered on sale; may be null
 * @param sgst SGST rate as text
 * @param cgst CGST rate as text
 * @param costPriceIncludesTax true when the entered cost includes GST
 */
@Builder
public record PricingCreate(
    String shopId,
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
    Boolean costPriceIncludesTax) {}
