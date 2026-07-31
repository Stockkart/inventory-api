package com.inventory.product.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.util.StringUtils;

/**
 * Resolves cash / online / credit legs for vendor purchase invoices. Prefers explicit split
 * fields; otherwise derives from {@code paymentMethod} + optional legacy {@code paidAmount}.
 */
public final class VendorPurchasePaymentBreakdown {

  private VendorPurchasePaymentBreakdown() {}

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
    BigDecimal total =
        (invoiceTotal != null ? invoiceTotal : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
    String method =
        StringUtils.hasText(rawMethod) ? rawMethod.trim().toUpperCase() : "CREDIT";

    boolean hasExplicitSplit =
        cashAmount != null || onlineAmount != null || creditAmount != null;
    if (hasExplicitSplit) {
      BigDecimal cash = cap(nz(cashAmount), total);
      BigDecimal online = cap(nz(onlineAmount), total);
      BigDecimal credit = cap(nz(creditAmount), total);
      BigDecimal sum = cash.add(online).add(credit);
      if (sum.compareTo(total) > 0) {
        // Prefer scaling to invoice total rather than failing stock-in.
        Result scaled = scaleToTotal(new Result(method, cash, online, credit, cash.add(online)), total);
        return scaled;
      }
      if (sum.compareTo(total) < 0) {
        credit = total.subtract(cash).subtract(online).setScale(4, RoundingMode.HALF_UP);
      }
      BigDecimal paid = cash.add(online).setScale(4, RoundingMode.HALF_UP);
      return new Result(method, cash, online, credit, paid);
    }

    return resolveFromMethodAndPaid(method, total, paidAmount);
  }

  /** Derive split for MIS reads when stored split fields are missing (legacy invoices). */
  public static Result deriveForReport(
      BigDecimal invoiceTotal, String paymentMethod, BigDecimal paidAmount) {
    return resolve(invoiceTotal, paymentMethod, paidAmount, null, null, null);
  }

  private static Result resolveFromMethodAndPaid(
      String method, BigDecimal total, BigDecimal paidAmount) {
    if (paidAmount != null && paidAmount.signum() >= 0) {
      BigDecimal paid = cap(paidAmount.setScale(4, RoundingMode.HALF_UP), total);
      BigDecimal credit = total.subtract(paid).setScale(4, RoundingMode.HALF_UP);
      return switch (method) {
        case "ONLINE", "UPI", "BANK", "CARD" ->
            new Result(method, zero(), paid, credit, paid);
        case "CASH_ONLINE" -> {
          BigDecimal half = paid.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
          yield new Result(method, half, paid.subtract(half), credit, paid);
        }
        case "ONLINE_CREDIT", "CREDIT_CASH", "CREDIT" -> {
          if ("ONLINE_CREDIT".equals(method)) {
            yield new Result(method, zero(), paid, credit, paid);
          }
          if ("CREDIT_CASH".equals(method)) {
            yield new Result(method, paid, zero(), credit, paid);
          }
          yield new Result(method, zero(), zero(), total, zero());
        }
        default -> new Result(method, paid, zero(), credit, paid);
      };
    }
    return switch (method) {
      case "ONLINE", "UPI", "BANK", "CARD" ->
          new Result(method, zero(), total, zero(), total);
      case "CREDIT" -> new Result(method, zero(), zero(), total, zero());
      case "CASH_ONLINE" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, half, total.subtract(half), zero(), total);
      }
      case "ONLINE_CREDIT" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, zero(), half, total.subtract(half), half);
      }
      case "CREDIT_CASH" -> {
        BigDecimal half = total.divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
        yield new Result(method, half, zero(), total.subtract(half), half);
      }
      default ->
          // B2B default when method absent historically was CREDIT after InventoryService change.
          "CASH".equals(method)
              ? new Result(method, total, zero(), zero(), total)
              : new Result(method, zero(), zero(), total, zero());
    };
  }

  private static Result scaleToTotal(Result tender, BigDecimal target) {
    BigDecimal sum =
        nz(tender.cashAmount()).add(nz(tender.onlineAmount())).add(nz(tender.creditAmount()));
    if (sum.signum() == 0) {
      return resolveFromMethodAndPaid(tender.paymentMethod(), target, null);
    }
    BigDecimal cash =
        nz(tender.cashAmount()).multiply(target).divide(sum, 4, RoundingMode.HALF_UP);
    BigDecimal online =
        nz(tender.onlineAmount()).multiply(target).divide(sum, 4, RoundingMode.HALF_UP);
    BigDecimal credit = target.subtract(cash).subtract(online).setScale(4, RoundingMode.HALF_UP);
    if (credit.signum() < 0) {
      credit = zero();
    }
    return new Result(
        tender.paymentMethod(), cash, online, credit, cash.add(online).setScale(4, RoundingMode.HALF_UP));
  }

  private static BigDecimal cap(BigDecimal amount, BigDecimal total) {
    BigDecimal v = nz(amount);
    return v.compareTo(total) > 0 ? total : v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return (v != null ? v : BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
  }
}
