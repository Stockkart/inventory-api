package com.inventory.pricing.utils;

import com.inventory.pricing.api.SystemRates;

/**
 * The batch's built-in rate options -- PTR, MRP and cost -- by every name they go by: the system
 * keys ({@code priceToRetail}, {@code maximumRetailPrice}, {@code costPrice}) and the labels the
 * counter shows ({@code PTR}, {@code MRP}, {@code Cost}), case-insensitively. No rule
 * ({@code RateRulesValidator}) or hand-typed rate ({@code PricingValidator}: on create, update,
 * {@code PATCH} and bulk-update) may take one of these names, and a cart pick by one of them means
 * the system option, never a stored rate of the same name. Rates stored before that check may
 * still carry one; checkout never bills them.
 */
public final class SystemRateNames {

  private SystemRateNames() {}

  /** The system key ({@code priceToRetail} …) {@code name} stands for, or null if none. */
  public static String systemKey(String name) {
    return SystemRates.systemKey(name);
  }

  /** Whether {@code name} is one of the built-in options' names, in any case. */
  public static boolean isReserved(String name) {
    return SystemRates.isReserved(name);
  }
}
