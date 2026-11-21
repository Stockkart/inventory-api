package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * A batch's prices as a rate rule preview takes them. The cost is worked out from
 * {@code costPrice} after the purchase discount and scheme, without GST when
 * {@code costPriceIncludesTax}.
 */
public record BatchPrices(
    BigDecimal sellingPrice,
    BigDecimal priceToRetail,
    BigDecimal maximumRetailPrice,
    BigDecimal costPrice,
    BigDecimal purchaseAdditionalDiscount,
    SchemeTerms purchaseScheme,
    boolean costPriceIncludesTax,
    String sgst,
    String cgst) {}
