package com.inventory.hr.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.bson.types.Decimal128;

/** Money helpers for HR. Amounts are always {@link BigDecimal} (P7); paise = scale 2. */
public final class HrMoneyUtils {

  private HrMoneyUtils() {}

  public static BigDecimal nz(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }

  /** One rounding to paise, HALF_UP (R8.15, R14.2). */
  public static BigDecimal paise(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  /** An aggregation result ({@code $sum} over Decimal128 gives Decimal128) as BigDecimal. */
  public static BigDecimal toBigDecimal(Object value) {
    if (value instanceof Decimal128 d) {
      return d.bigDecimalValue();
    }
    if (value instanceof BigDecimal b) {
      return b;
    }
    if (value instanceof Number n) {
      return new BigDecimal(n.toString());
    }
    return BigDecimal.ZERO;
  }
}
