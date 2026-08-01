package com.inventory.product.service;

import com.inventory.common.constants.PaymentMethod;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Resolves cash / online / credit legs for vendor purchase invoices. Prefers explicit split
 * fields; otherwise derives from {@code paymentMethod} + optional legacy {@code paidAmount}.
 *
 * <p>{@link Result#paymentMethod()} stays a string so an unrecognised historical method still
 * round-trips unchanged; only the branching is typed via {@link PaymentMethod}.
 */
public final class VendorPurchasePaymentBreakdown {

  private VendorPurchasePaymentBreakdown() {}

  /** Money is held at 4dp internally so halving a split tender does not lose paise. */
  private static final int MONEY_SCALE = 4;

  private static final BigDecimal TWO = BigDecimal.valueOf(2);

  /** Method assumed when an invoice records none — B2B purchases default to credit. */
  private static final PaymentMethod DEFAULT_METHOD = PaymentMethod.CREDIT;

  public record Result(
      String paymentMethod,
      BigDecimal cashAmount,
      BigDecimal onlineAmount,
      BigDecimal creditAmount,
      BigDecimal paidAmount) {}

  public static Result resolve(
      BigDecimal invoiceTotal,
      String rawMethod,
      BigDecimal paidAmount,
      BigDecimal cashAmount,
      BigDecimal onlineAmount,
      BigDecimal creditAmount) {
    BigDecimal total = toMoneyScale(invoiceTotal);
    String method = PaymentMethod.normalise(rawMethod, DEFAULT_METHOD);

    boolean hasExplicitSplit = cashAmount != null || onlineAmount != null || creditAmount != null;
    if (hasExplicitSplit) {
      return resolveFromExplicitSplit(method, total, cashAmount, onlineAmount, creditAmount);
    }

    return resolveFromMethodAndPaid(method, total, paidAmount);
  }

  /** Derive split for MIS reads when stored split fields are missing (legacy invoices). */
  public static Result deriveForReport(
      BigDecimal invoiceTotal, String paymentMethod, BigDecimal paidAmount) {
    return resolve(invoiceTotal, paymentMethod, paidAmount, null, null, null);
  }

  /**
   * Uses the legs recorded on the invoice, reconciling them against the total.
   *
   * <p>An over-total split is scaled down rather than rejected — refusing here would fail the
   * stock-in that produced it, long after the user could do anything about it.
   */
  private static Result resolveFromExplicitSplit(
      String method,
      BigDecimal total,
      BigDecimal cashAmount,
      BigDecimal onlineAmount,
      BigDecimal creditAmount) {
    BigDecimal cash = clampToTotal(zeroIfNull(cashAmount), total);
    BigDecimal online = clampToTotal(zeroIfNull(onlineAmount), total);
    BigDecimal credit = clampToTotal(zeroIfNull(creditAmount), total);

    BigDecimal sum = cash.add(online).add(credit);
    if (sum.compareTo(total) > 0) {
      return scaleToTotal(new Result(method, cash, online, credit, cash.add(online)), total);
    }
    if (sum.compareTo(total) < 0) {
      // Under-recorded legs mean the remainder is still owed.
      credit = toMoneyScale(total.subtract(cash).subtract(online));
    }
    return new Result(method, cash, online, credit, toMoneyScale(cash.add(online)));
  }

  private static Result resolveFromMethodAndPaid(
      String rawMethod, BigDecimal total, BigDecimal paidAmount) {
    PaymentMethod method = PaymentMethod.from(rawMethod, DEFAULT_METHOD);
    boolean recognised = PaymentMethod.parse(rawMethod).isPresent();

    if (paidAmount != null && paidAmount.signum() >= 0) {
      return withKnownPaidAmount(rawMethod, method, recognised, total, paidAmount);
    }
    return fromMethodAlone(rawMethod, method, recognised, total);
  }

  /** A paid amount is on record, so only its allocation across legs is in question. */
  private static Result withKnownPaidAmount(
      String rawMethod,
      PaymentMethod method,
      boolean recognised,
      BigDecimal total,
      BigDecimal paidAmount) {
    BigDecimal paid = clampToTotal(toMoneyScale(paidAmount), total);
    BigDecimal credit = toMoneyScale(total.subtract(paid));

    if (!recognised) {
      // Unknown method with money recorded against it: treat as cash.
      return new Result(rawMethod, paid, zero(), credit, paid);
    }

    return switch (method) {
      case ONLINE, UPI, BANK, CARD, ONLINE_CREDIT ->
          new Result(rawMethod, zero(), paid, credit, paid);
      case CASH_ONLINE -> {
        BigDecimal half = half(paid);
        yield new Result(rawMethod, half, paid.subtract(half), credit, paid);
      }
      case CREDIT_CASH -> new Result(rawMethod, paid, zero(), credit, paid);
      case CREDIT -> new Result(rawMethod, zero(), zero(), total, zero());
      case CASH -> new Result(rawMethod, paid, zero(), credit, paid);
    };
  }

  /** Nothing recorded as paid, so the method alone decides the split. */
  private static Result fromMethodAlone(
      String rawMethod, PaymentMethod method, boolean recognised, BigDecimal total) {
    if (!recognised) {
      // Historically an absent or unknown method on a B2B purchase meant credit.
      return new Result(rawMethod, zero(), zero(), total, zero());
    }

    return switch (method) {
      case ONLINE, UPI, BANK, CARD -> new Result(rawMethod, zero(), total, zero(), total);
      case CREDIT -> new Result(rawMethod, zero(), zero(), total, zero());
      case CASH -> new Result(rawMethod, total, zero(), zero(), total);
      case CASH_ONLINE -> {
        BigDecimal half = half(total);
        yield new Result(rawMethod, half, total.subtract(half), zero(), total);
      }
      case ONLINE_CREDIT -> {
        BigDecimal half = half(total);
        yield new Result(rawMethod, zero(), half, total.subtract(half), half);
      }
      case CREDIT_CASH -> {
        BigDecimal half = half(total);
        yield new Result(rawMethod, half, zero(), total.subtract(half), half);
      }
    };
  }

  /** Proportionally reduces an over-total split so the legs sum to the invoice total. */
  private static Result scaleToTotal(Result tender, BigDecimal target) {
    BigDecimal sum =
        zeroIfNull(tender.cashAmount())
            .add(zeroIfNull(tender.onlineAmount()))
            .add(zeroIfNull(tender.creditAmount()));
    if (sum.signum() == 0) {
      return resolveFromMethodAndPaid(tender.paymentMethod(), target, null);
    }
    BigDecimal cash = proportion(tender.cashAmount(), target, sum);
    BigDecimal online = proportion(tender.onlineAmount(), target, sum);
    BigDecimal credit = toMoneyScale(target.subtract(cash).subtract(online));
    if (credit.signum() < 0) {
      credit = zero();
    }
    return new Result(
        tender.paymentMethod(), cash, online, credit, toMoneyScale(cash.add(online)));
  }

  private static BigDecimal proportion(BigDecimal leg, BigDecimal target, BigDecimal sum) {
    return zeroIfNull(leg).multiply(target).divide(sum, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal half(BigDecimal amount) {
    return amount.divide(TWO, MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** Caps a leg at the invoice total; a leg alone can never exceed what was billed. */
  private static BigDecimal clampToTotal(BigDecimal amount, BigDecimal total) {
    BigDecimal value = zeroIfNull(amount);
    return value.compareTo(total) > 0 ? total : value;
  }

  private static BigDecimal zeroIfNull(BigDecimal value) {
    return toMoneyScale(value);
  }

  private static BigDecimal toMoneyScale(BigDecimal value) {
    return (value != null ? value : BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
