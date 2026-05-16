package com.inventory.accounting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Money arithmetic helpers — always scale 4 internally, HALF_UP. */
final class MoneyUtil {

  static final int SCALE = 4;

  private MoneyUtil() {}

  static BigDecimal scale(BigDecimal v) {
    return (v == null ? BigDecimal.ZERO : v).setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal nz(BigDecimal v) {
    return v == null ? zero() : scale(v);
  }
}
