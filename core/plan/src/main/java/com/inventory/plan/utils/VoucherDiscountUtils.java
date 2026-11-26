package com.inventory.plan.utils;

import com.inventory.plan.domain.model.AddOnVoucher;
import com.inventory.plan.domain.model.OrderLine;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** Voucher discount maths shared by the voucher service and plan-voucher pricing. */
public final class VoucherDiscountUtils {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private VoucherDiscountUtils() {
  }

  /** Discount the voucher gives on {@code line}, never more than the line's gross amount. */
  public static BigDecimal discountFor(AddOnVoucher voucher, OrderLine line) {
    BigDecimal gross = line.getUnitPrice().multiply(BigDecimal.valueOf(line.getQuantity()));
    BigDecimal discount = switch (voucher.getType()) {
      case FREE_ADDON -> line.getUnitPrice().multiply(BigDecimal.valueOf(
          Math.min(line.getQuantity(), Math.max(1, voucher.getQuantity()))));
      case PERCENT_OFF -> gross.multiply(voucher.getValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
      case FLAT_OFF -> voucher.getValue();
    };
    return discount.min(gross).max(BigDecimal.ZERO);
  }
}
