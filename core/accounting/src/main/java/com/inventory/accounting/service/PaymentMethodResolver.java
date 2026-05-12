package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.DefaultAccountCodes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Resolves payment method strings into GL account codes and computes debit/credit line splits
 * for both single and combination payment modes.
 *
 * <p>Supported modes: CASH, ONLINE, CREDIT, CASH_ONLINE, ONLINE_CREDIT, CREDIT_CASH.
 */
public final class PaymentMethodResolver {

  private PaymentMethodResolver() {}

  public static final int MONEY_SCALE = PostingService.MONEY_SCALE;

  /**
   * Represents a single receipt line: which GL code to debit and for how much.
   * For credit portions, the code is RECEIVABLES (sale) or PAYABLES (purchase).
   */
  public record ReceiptAllocation(String glAccountCode, BigDecimal amount, boolean isCredit) {}

  /**
   * Resolves a single payment method to its GL account code.
   * CASH → CASH, ONLINE → user-selected bank (or fallback BANK), CREDIT → RECEIVABLES.
   */
  public static String glAccountForMethod(String method) {
    return glAccountForMethod(method, null);
  }

  public static String glAccountForMethod(String method, String bankAccountCode) {
    if (method == null) return DefaultAccountCodes.CASH;
    switch (method.trim().toUpperCase()) {
      case "ONLINE":
        return resolveBankCode(bankAccountCode);
      case "CREDIT":
        return DefaultAccountCodes.ACCOUNTS_RECEIVABLE;
      case "CASH":
      default:
        return DefaultAccountCodes.CASH;
    }
  }

  /**
   * Returns true if the payment method involves any credit component.
   */
  public static boolean hasCredit(String paymentMethod) {
    if (paymentMethod == null) return false;
    String m = paymentMethod.trim().toUpperCase();
    return "CREDIT".equals(m) || "ONLINE_CREDIT".equals(m) || "CREDIT_CASH".equals(m);
  }

  /**
   * Returns true if the payment method is a combination mode.
   */
  public static boolean isComboMode(String paymentMethod) {
    if (paymentMethod == null) return false;
    String m = paymentMethod.trim().toUpperCase();
    return "CASH_ONLINE".equals(m) || "ONLINE_CREDIT".equals(m) || "CREDIT_CASH".equals(m);
  }

  /**
   * Backward-compatible overload — uses the default BANK account for online payments.
   */
  public static List<ReceiptAllocation> resolveAllocations(
      String paymentMethod,
      BigDecimal grandTotal,
      Map<String, BigDecimal> splitAmounts,
      BigDecimal paidNowOverride) {
    return resolveAllocations(paymentMethod, grandTotal, splitAmounts, paidNowOverride, null);
  }

  /**
   * Resolves the payment method + splitAmounts into receipt allocations.
   *
   * @param paymentMethod   the payment method string
   * @param grandTotal      the invoice total
   * @param splitAmounts    breakdown map for combo modes (nullable for single modes)
   * @param paidNowOverride explicit paid-now for single CREDIT mode (nullable)
   * @param bankAccountCode user-selected bank GL code for online payments (nullable — falls back to BANK)
   * @return ordered list of allocations (immediate receipt first, then credit if applicable)
   */
  public static List<ReceiptAllocation> resolveAllocations(
      String paymentMethod,
      BigDecimal grandTotal,
      Map<String, BigDecimal> splitAmounts,
      BigDecimal paidNowOverride,
      String bankAccountCode) {

    BigDecimal total = scale(nz(grandTotal));
    if (total.signum() <= 0) {
      return Collections.emptyList();
    }

    String method = normalize(paymentMethod);
    String bankCode = resolveBankCode(bankAccountCode);

    switch (method) {
      case "CASH":
        return List.of(new ReceiptAllocation(DefaultAccountCodes.CASH, total, false));

      case "ONLINE":
        return List.of(new ReceiptAllocation(bankCode, total, false));

      case "CREDIT": {
        BigDecimal paid = scale(nz(paidNowOverride));
        BigDecimal credit = scale(total.subtract(paid));
        List<ReceiptAllocation> result = new ArrayList<>();
        if (paid.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.CASH, paid, false));
        }
        if (credit.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.ACCOUNTS_RECEIVABLE, credit, true));
        }
        return result;
      }

      case "CASH_ONLINE": {
        BigDecimal cashAmt = splitAmount(splitAmounts, "CASH");
        BigDecimal onlineAmt = splitAmount(splitAmounts, "ONLINE");
        if (cashAmt.signum() <= 0 && onlineAmt.signum() <= 0) {
          BigDecimal half = scale(total.divide(BigDecimal.valueOf(2), MONEY_SCALE, RoundingMode.HALF_UP));
          cashAmt = half;
          onlineAmt = scale(total.subtract(half));
        }
        List<ReceiptAllocation> result = new ArrayList<>();
        if (cashAmt.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.CASH, cashAmt, false));
        }
        if (onlineAmt.signum() > 0) {
          result.add(new ReceiptAllocation(bankCode, onlineAmt, false));
        }
        return result;
      }

      case "ONLINE_CREDIT": {
        BigDecimal onlineAmt = splitAmount(splitAmounts, "ONLINE");
        if (onlineAmt.signum() <= 0) {
          onlineAmt = scale(nz(paidNowOverride));
        }
        BigDecimal credit = scale(total.subtract(onlineAmt));
        List<ReceiptAllocation> result = new ArrayList<>();
        if (onlineAmt.signum() > 0) {
          result.add(new ReceiptAllocation(bankCode, onlineAmt, false));
        }
        if (credit.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.ACCOUNTS_RECEIVABLE, credit, true));
        }
        return result;
      }

      case "CREDIT_CASH": {
        BigDecimal cashAmt = splitAmount(splitAmounts, "CASH");
        if (cashAmt.signum() <= 0) {
          cashAmt = scale(nz(paidNowOverride));
        }
        BigDecimal credit = scale(total.subtract(cashAmt));
        List<ReceiptAllocation> result = new ArrayList<>();
        if (cashAmt.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.CASH, cashAmt, false));
        }
        if (credit.signum() > 0) {
          result.add(new ReceiptAllocation(DefaultAccountCodes.ACCOUNTS_RECEIVABLE, credit, true));
        }
        return result;
      }

      default:
        return List.of(new ReceiptAllocation(DefaultAccountCodes.CASH, total, false));
    }
  }

  /** Returns the user-selected bank code, or the default BANK if not provided. */
  private static String resolveBankCode(String bankAccountCode) {
    return (bankAccountCode != null && !bankAccountCode.isBlank())
        ? bankAccountCode.trim().toUpperCase()
        : DefaultAccountCodes.BANK;
  }

  /**
   * Computes the total amount received immediately (non-credit portion).
   */
  public static BigDecimal computePaidNow(List<ReceiptAllocation> allocations) {
    return allocations.stream()
        .filter(a -> !a.isCredit())
        .map(ReceiptAllocation::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Computes the credit (unpaid) portion.
   */
  public static BigDecimal computeCreditAmount(List<ReceiptAllocation> allocations) {
    return allocations.stream()
        .filter(ReceiptAllocation::isCredit)
        .map(ReceiptAllocation::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal splitAmount(Map<String, BigDecimal> splits, String key) {
    if (splits == null || splits.isEmpty()) return BigDecimal.ZERO;
    BigDecimal v = splits.get(key);
    if (v == null) {
      v = splits.get(key.toLowerCase());
    }
    return v != null && v.signum() >= 0
        ? v.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        : BigDecimal.ZERO;
  }

  private static String normalize(String raw) {
    if (raw == null || raw.isBlank()) return "CASH";
    return raw.trim().toUpperCase();
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
