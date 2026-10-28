package com.inventory.pricing.api;

import java.math.BigDecimal;

/**
 * One named rate of a stored pricing record, as other modules read it: every field of the stored
 * rate.
 *
 * @param name rate name, e.g. {@code Wholesale}
 * @param price the price this rate sells at
 * @param base what the rate is worked from, e.g. {@code sellingPrice}; null for a typed price
 * @param baseValue the base price it was worked from
 * @param discountPercent discount off the base
 * @param scheme scheme offered at this rate; may be null
 * @param manual true when the price was typed rather than generated from a shop rule
 * @param gstMode {@code INCLUSIVE} or {@code EXCLUSIVE}
 */
public record RateView(
    String name,
    BigDecimal price,
    String base,
    BigDecimal baseValue,
    BigDecimal discountPercent,
    SchemeTerms scheme,
    Boolean manual,
    String gstMode) {}
