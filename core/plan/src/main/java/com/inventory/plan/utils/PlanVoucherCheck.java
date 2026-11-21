package com.inventory.plan.utils;

import java.math.BigDecimal;

/** Outcome of checking a plan voucher against the plan's price and base-price floor. All amounts scale 2. */
public record PlanVoucherCheck(
    BigDecimal price,
    BigDecimal discount,
    BigDecimal priceAfter,
    BigDecimal basePrice,
    BigDecimal floor,
    BigDecimal maxFlat,
    BigDecimal maxPercent,
    boolean allowed) {
}
