package com.inventory.pricing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import lombok.Builder;

/**
 * A lot's stored pricing record, as other modules read it: every field of the stored record.
 * Read through {@link PricingLookup}.
 *
 * @param id pricing id (an inventory lot's {@code pricingId})
 * @param shopId shop
 * @param maximumRetailPrice MRP
 * @param costPrice cost as entered off the vendor's bill
 * @param effectiveCostPrice landed cost per unit, see {@link LandedCost}
 * @param costPriceIncludesTax true when the entered cost includes GST
 * @param priceToRetail price to retailer
 * @param rates named rates
 * @param defaultRate which price the lot sells at by default
 * @param sellingPrice selling price
 * @param saleAdditionalDiscount additional discount on sale, percent
 * @param purchaseAdditionalDiscount additional discount on purchase, percent
 * @param purchaseScheme the vendor's scheme on purchase; may be null
 * @param saleScheme the scheme offered on sale; may be null
 * @param sgst SGST rate as text, e.g. {@code "6"}
 * @param cgst CGST rate as text
 * @param createdAt when the record was created
 * @param updatedAt when it last changed
 */
@Builder
public record PricingView(
    String id,
    String shopId,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal effectiveCostPrice,
    Boolean costPriceIncludesTax,
    BigDecimal priceToRetail,
    List<RateView> rates,
    String defaultRate,
    BigDecimal sellingPrice,
    BigDecimal saleAdditionalDiscount,
    BigDecimal purchaseAdditionalDiscount,
    SchemeTerms purchaseScheme,
    SchemeTerms saleScheme,
    String sgst,
    String cgst,
    Instant createdAt,
    Instant updatedAt) {}
