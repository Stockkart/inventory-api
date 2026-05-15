package com.inventory.product.service;

import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.util.StringUtils;

/**
 * Resolves cash / online / credit legs for sale and purchase returns. Mirrors checkout split
 * semantics: prefers explicit {@code cashAmount}/{@code onlineAmount}/{@code creditAmount};
 * otherwise derives from the canonical payment method and return total.
 */
public final class ReturnPaymentBreakdown {

  private ReturnPaymentBreakdown() {}

  public record Result(
      String paymentMethod,
      BigDecimal refundCash,
      BigDecimal refundOnline,
      BigDecimal refundToCredit) {}

  public static Result resolve(
      BigDecimal returnTotal,
      String rawMethod,
      BigDecimal cashAmount,
      BigDecimal onlineAmount,
      BigDecimal creditAmount) {
    if (returnTotal == null || returnTotal.signum() <= 0) {
      throw new ValidationException("Return total must be greater than zero");
    }
    if (!StringUtils.hasText(rawMethod)) {
      throw new ValidationException("paymentMethod is required for returns");
    }
    String method = rawMethod.trim().toUpperCase();
    BigDecimal total = returnTotal.setScale(4, RoundingMode.HALF_UP);

    boolean hasExplicitSplit =
        cashAmount != null || onlineAmount != null || creditAmount != null;
    if (hasExplicitSplit) {
      BigDecimal cash = capTender(nz(cashAmount), total);
      BigDecimal online = capTender(nz(onlineAmount), total);
      BigDecimal credit = capTender(nz(creditAmount), total);
      BigDecimal sum = cash.add(online).add(credit);
      if (sum.compareTo(total) > 0) {
        throw new ValidationException(
            "Refund split (cash + online + credit) cannot exceed return total");
      }
      if (sum.compareTo(total) < 0) {
        throw new ValidationException(
            "Refund split must equal return total ("
                + sum.setScale(2, RoundingMode.HALF_UP)
                + " vs "
                + total.setScale(2, RoundingMode.HALF_UP)
                + ")");
      }
      return new Result(method, cash, online, credit);
    }

    return resolveFromMethodOnly(method, total);
  }

  /**
   * When the computed return total differs from the amount the client split against (e.g. whole-rupee
   * rounding on sales returns), scale legs proportionally so they still sum to {@code returnTotal}.
   */
  public static Result scaleToTotal(Result tender, BigDecimal returnTotal) {
    if (tender == null) {
      throw new ValidationException("Refund tender is required");
    }
    BigDecimal target = returnTotal.setScale(4, RoundingMode.HALF_UP);
    BigDecimal sum =
        nz(tender.refundCash()).add(nz(tender.refundOnline())).add(nz(tender.refundToCredit()));
    if (sum.compareTo(target) == 0) {
      return tender;
    }
    if (sum.signum() == 0) {
      return resolveFromMethodOnly(tender.paymentMethod(), target);
    }
    BigDecimal cash =
        nz(tender.refundCash()).multiply(target).divide(sum, 4, RoundingMode.HALF_UP);
    BigDecimal online =
        nz(tender.refundOnline()).multiply(target).divide(sum, 4, RoundingMode.HALF_UP);
    BigDecimal credit = target.subtract(cash).subtract(online).setScale(4, RoundingMode.HALF_UP);
    if (credit.signum() < 0) {
      if (cash.compareTo(credit.negate()) >= 0) {
        cash = cash.add(credit);
      } else {
        online = online.add(cash).add(credit);
        cash = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
      }
      credit = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    }
    return new Result(tender.paymentMethod(), cash, online, credit);
  }

  private static Result resolveFromMethodOnly(String method, BigDecimal total) {
    return switch (method) {
      case "ONLINE", "UPI", "BANK", "CARD" ->
          new Result(method, BigDecimal.ZERO, total, BigDecimal.ZERO);
      case "CREDIT" -> new Result(method, BigDecimal.ZERO, BigDecimal.ZERO, total);
      case "CASH_ONLINE" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, half, total.subtract(half), BigDecimal.ZERO);
      }
      case "ONLINE_CREDIT" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, BigDecimal.ZERO, half, total.subtract(half));
      }
      case "CREDIT_CASH" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, half, BigDecimal.ZERO, total.subtract(half));
      }
      default -> new Result(method, total, BigDecimal.ZERO, BigDecimal.ZERO);
    };
  }

  private static BigDecimal capTender(BigDecimal amount, BigDecimal total) {
    BigDecimal v = nz(amount);
    if (v.compareTo(total) > 0) {
      return total;
    }
    return v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return (v != null ? v : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
  }
}
