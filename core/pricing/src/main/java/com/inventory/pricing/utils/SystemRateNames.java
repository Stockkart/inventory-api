package com.inventory.pricing.utils;

import com.inventory.pricing.utils.constants.PricingConstants;
import java.util.Locale;
import java.util.Map;

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

  private static final Map<String, String> KEYS = Map.of(
      PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL.toLowerCase(Locale.ROOT),
      PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL,
      PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE.toLowerCase(Locale.ROOT),
      PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE,
      PricingConstants.DEFAULT_RATE_COST_PRICE.toLowerCase(Locale.ROOT),
      PricingConstants.DEFAULT_RATE_COST_PRICE,
      "ptr", PricingConstants.DEFAULT_RATE_PRICE_TO_RETAIL,
      "mrp", PricingConstants.DEFAULT_RATE_MAXIMUM_RETAIL_PRICE,
      "cost", PricingConstants.DEFAULT_RATE_COST_PRICE);

  private SystemRateNames() {}

  /** The system key ({@code priceToRetail} …) {@code name} stands for, or null if none. */
  public static String systemKey(String name) {
    if (name == null) {
      return null;
    }
    return KEYS.get(name.trim().toLowerCase(Locale.ROOT));
  }

  /** Whether {@code name} is one of the built-in options' names, in any case. */
  public static boolean isReserved(String name) {
    return systemKey(name) != null;
  }
}
