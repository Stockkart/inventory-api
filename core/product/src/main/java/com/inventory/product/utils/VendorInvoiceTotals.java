package com.inventory.product.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a supplier invoice comes to, from its header.
 *
 * <p>One definition for every place that needs it: the credit charge and journal at stock-in, and
 * the stock-in screen's preview, so the figure an operator sees is the figure that is posted.
 */
public final class VendorInvoiceTotals {

  private VendorInvoiceTotals() {}

  /**
   * The stated invoice total when there is one; otherwise subtotal plus tax, shipping, other
   * charges and round-off, less the overall discount, never below zero. At 4dp.
   */
  public static BigDecimal invoiceTotal(
      BigDecimal statedTotal,
      BigDecimal lineSubTotal,
      BigDecimal taxTotal,
      BigDecimal shippingCharge,
      BigDecimal otherCharges,
      BigDecimal roundOff,
      BigDecimal overallDiscount) {
    BigDecimal stated = nz(statedTotal);
    if (stated.signum() > 0) {
      return stated.setScale(4, RoundingMode.HALF_UP);
    }
    return nz(lineSubTotal)
        .add(nz(taxTotal))
        .add(nz(shippingCharge))
        .add(nz(otherCharges))
        .add(nz(roundOff))
        .subtract(nz(overallDiscount))
        .max(BigDecimal.ZERO)
        .setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
