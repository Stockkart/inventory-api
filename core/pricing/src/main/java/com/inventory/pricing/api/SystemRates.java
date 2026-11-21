package com.inventory.pricing.api;

import java.util.Locale;

/**
 * The batch's built-in rate options (PTR, MRP and cost) by every name they go by: the system keys
 * ({@link #PRICE_TO_RETAIL} …) and the counter labels ({@code PTR}, {@code MRP}, {@code Cost}),
 * case-insensitively. No rule or hand-typed rate may take one of these names, and a cart pick by
 * one of them means the system option, never a stored rate of the same name.
 */
public interface SystemRates {

  String PRICE_TO_RETAIL = "priceToRetail";
  String MAXIMUM_RETAIL_PRICE = "maximumRetailPrice";
  String COST_PRICE = "costPrice";

  /** The system key {@code name} stands for, or null if none. */
  static String systemKey(String name) {
    if (name == null) {
      return null;
    }
    return switch (name.trim().toLowerCase(Locale.ROOT)) {
      case "pricetoretail", "ptr" -> PRICE_TO_RETAIL;
      case "maximumretailprice", "mrp" -> MAXIMUM_RETAIL_PRICE;
      case "costprice", "cost" -> COST_PRICE;
      default -> null;
    };
  }

  /** Whether {@code name} is one of the built-in options' names, in any case. */
  static boolean isReserved(String name) {
    return systemKey(name) != null;
  }

}
