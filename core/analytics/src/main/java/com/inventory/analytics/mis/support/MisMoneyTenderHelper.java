package com.inventory.analytics.mis.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** Maps payment-method strings to cash / online buckets. */
public final class MisMoneyTenderHelper {

  private static final int SCALE = 2;

  private MisMoneyTenderHelper() {}

  public static BigDecimal nz(BigDecimal v) {
    return v != null ? v.setScale(SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(SCALE);
  }

  public static boolean isOnlineMethod(String paymentMethod) {
    if (!StringUtils.hasText(paymentMethod)) {
      return false;
    }
    String m = paymentMethod.trim().toUpperCase(Locale.ROOT);
    return m.equals("UPI")
        || m.equals("BANK")
        || m.equals("CARD")
        || m.equals("ONLINE")
        || m.equals("NEFT")
        || m.equals("RTGS")
        || m.equals("IMPS")
        || m.contains("ONLINE");
  }

  public static boolean isCashMethod(String paymentMethod) {
    if (!StringUtils.hasText(paymentMethod)) {
      return false;
    }
    String m = paymentMethod.trim().toUpperCase(Locale.ROOT);
    return m.equals("CASH");
  }

  public static boolean isAdjustmentMethod(String paymentMethod) {
    return StringUtils.hasText(paymentMethod)
        && "ADJUSTMENT".equalsIgnoreCase(paymentMethod.trim());
  }

  /**
   * Splits a single paid amount + method into cash/online (credit is computed by caller as
   * remainder).
   */
  public static TenderSplit splitPaid(BigDecimal paidAmount, String paymentMethod) {
    BigDecimal paid = nz(paidAmount);
    if (paid.signum() <= 0) {
      return new TenderSplit(BigDecimal.ZERO.setScale(SCALE), BigDecimal.ZERO.setScale(SCALE));
    }
    if (isAdjustmentMethod(paymentMethod)) {
      return new TenderSplit(BigDecimal.ZERO.setScale(SCALE), BigDecimal.ZERO.setScale(SCALE));
    }
    if (isOnlineMethod(paymentMethod)) {
      return new TenderSplit(BigDecimal.ZERO.setScale(SCALE), paid);
    }
    // Default cash (including blank method)
    return new TenderSplit(paid, BigDecimal.ZERO.setScale(SCALE));
  }

  public record TenderSplit(BigDecimal cash, BigDecimal online) {}
}
