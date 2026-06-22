package com.inventory.pluginengine.cart;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.util.StringUtils;

public final class CartLineAmountCalculator {

  private CartLineAmountCalculator() {}

  public static BigDecimal lineTotal(
      BigDecimal priceToRetail,
      BigDecimal additionalDiscountPercent,
      BigDecimal quantity,
      String cgst,
      String sgst) {
    if (priceToRetail == null || quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal unitPrice = priceToRetail;
    if (additionalDiscountPercent != null && additionalDiscountPercent.compareTo(BigDecimal.ZERO) != 0) {
      BigDecimal multiplier =
          BigDecimal.ONE.subtract(
              additionalDiscountPercent.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
      unitPrice = priceToRetail.multiply(multiplier);
    }
    BigDecimal subtotal = unitPrice.multiply(quantity).setScale(2, RoundingMode.HALF_UP);
    BigDecimal tax = taxOnAmount(subtotal, cgst).add(taxOnAmount(subtotal, sgst));
    return subtotal.add(tax).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal taxOnAmount(BigDecimal amount, String ratePercent) {
    if (!StringUtils.hasText(ratePercent)) {
      return BigDecimal.ZERO;
    }
    try {
      BigDecimal rate =
          new BigDecimal(ratePercent.trim()).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
      return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }
}
