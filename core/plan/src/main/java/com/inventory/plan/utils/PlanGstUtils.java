package com.inventory.plan.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * GST inside a tax-inclusive plan price. Plan prices and order totals include GST, so the taxable
 * value is {@code total * 100 / (100 + rate)} rounded to paise (HALF_UP) and GST is the rest: the two
 * always add up to the total exactly.
 */
public final class PlanGstUtils {

  private PlanGstUtils() {}

  /** Taxable value and GST of a tax-inclusive amount. */
  public record GstSplit(BigDecimal taxable, BigDecimal gst) {
    public static final GstSplit ZERO = new GstSplit(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
  }

  /** Splits {@code inclusiveTotal} at {@code ratePercent}; null or zero total gives a zero split. */
  public static GstSplit splitInclusive(BigDecimal inclusiveTotal, BigDecimal ratePercent) {
    if (inclusiveTotal == null || inclusiveTotal.signum() == 0) {
      return GstSplit.ZERO;
    }
    BigDecimal total = inclusiveTotal.setScale(2, RoundingMode.HALF_UP);
    BigDecimal rate = ratePercent == null ? BigDecimal.ZERO : ratePercent;
    BigDecimal hundred = BigDecimal.valueOf(100);
    BigDecimal taxable = total.multiply(hundred).divide(hundred.add(rate), 2, RoundingMode.HALF_UP);
    return new GstSplit(taxable, total.subtract(taxable));
  }
}
