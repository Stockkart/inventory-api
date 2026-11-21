package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * A rate a rule generates for a batch. {@code afterDiscount} is the base less the discount, before
 * the scheme; {@code termsText} reads like "PTR 100.00 − 8% · 10+1".
 */
public record GeneratedRate(
    String name,
    String base,
    BigDecimal baseValue,
    BigDecimal afterDiscount,
    BigDecimal price,
    BigDecimal discountPercent,
    SchemeTerms scheme,
    String gstMode,
    String termsText) {}
