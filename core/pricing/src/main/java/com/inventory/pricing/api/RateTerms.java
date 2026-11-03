package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * A batch's named rate as other modules read it. {@code base}, {@code baseValue},
 * {@code discountPercent} and {@code scheme} are null on a legacy, hand-typed rate.
 */
public record RateTerms(
    String name,
    BigDecimal price,
    String base,
    BigDecimal baseValue,
    BigDecimal discountPercent,
    SchemeTerms scheme) {}
