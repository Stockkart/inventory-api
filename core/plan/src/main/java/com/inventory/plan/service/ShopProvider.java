package com.inventory.plan.service;

import java.util.Optional;

/**
 * Provides shop data to the plan module. Implemented by the product module to avoid circular dependency.
 */
public interface ShopProvider {

  Optional<ShopInfo> getShop(String shopId);

  void updatePlan(String shopId, String planId, java.time.Instant expiryDate);

  /** Visits every shop, page by page. */
  default void forEachShop(java.util.function.Consumer<ShopInfo> action) {}

  /** Minimal shop info needed for plan/usage logic. */
  record ShopInfo(String shopId, String planId, java.time.Instant planExpiryDate) {}
}
