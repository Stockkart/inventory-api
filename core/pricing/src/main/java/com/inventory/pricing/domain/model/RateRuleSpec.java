package com.inventory.pricing.domain.model;

import java.math.BigDecimal;

/** One shop rate rule as the calculator reads it: a name, a base, an optional discount and scheme. */
public record RateRuleSpec(String name, String base, BigDecimal discountPercent, Scheme scheme) {}
