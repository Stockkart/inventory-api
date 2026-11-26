package com.inventory.plan.service.wallet;

import com.inventory.plan.domain.model.ShopCredit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Wallet arithmetic (r4.3, §27.4). Each move returns the new balances, or empty when not allowed. */
public record WalletBalances(BigDecimal available, BigDecimal reserved, BigDecimal outstanding) {

  public static final WalletBalances ZERO = new WalletBalances(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

  public WalletBalances {
    available = scale(available);
    reserved = scale(reserved);
    outstanding = scale(outstanding);
  }

  public static WalletBalances of(ShopCredit credit) {
    return new WalletBalances(credit.getAvailableBalance(), credit.getReservedBalance(), credit.getOutstandingClawback());
  }

  public static BigDecimal scale(BigDecimal amount) {
    return (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
  }

  /** Available → reserved, only if enough is available. */
  public Optional<WalletBalances> reserve(BigDecimal amount) {
    if (available.compareTo(amount) < 0) {
      return Optional.empty();
    }
    return Optional.of(new WalletBalances(available.subtract(amount), reserved.add(amount), outstanding));
  }

  /** Reserved → spent. */
  public Optional<WalletBalances> consume(BigDecimal amount) {
    if (reserved.compareTo(amount) < 0) {
      return Optional.empty();
    }
    return Optional.of(new WalletBalances(available, reserved.subtract(amount), outstanding));
  }

  /** Reserved → back to the shop; any outstanding clawback is paid off first. */
  public Optional<WalletBalances> release(BigDecimal amount) {
    if (reserved.compareTo(amount) < 0) {
      return Optional.empty();
    }
    return Optional.of(new WalletBalances(available, reserved.subtract(amount), outstanding).settleThenAdd(amount));
  }

  /**
   * Takes money back from what is available only; reserved credit belongs to in-flight orders. The
   * part not covered becomes outstanding, so the balance never goes negative (§27.4).
   */
  public WalletBalances clawback(BigDecimal amount) {
    BigDecimal taken = available.min(amount);
    return new WalletBalances(available.subtract(taken), reserved, outstanding.add(amount.subtract(taken)));
  }

  /** New money in; any outstanding clawback is paid off first. */
  public WalletBalances credit(BigDecimal amount) {
    return settleThenAdd(amount);
  }

  private WalletBalances settleThenAdd(BigDecimal amount) {
    BigDecimal payoff = outstanding.min(amount);
    return new WalletBalances(available.add(amount.subtract(payoff)), reserved, outstanding.subtract(payoff));
  }
}
