package com.inventory.plan.service;

import java.util.Optional;

/**
 * Provides shop data to the plan module. Implemented by the product module to avoid circular dependency.
 */
public interface ShopProvider {

  Optional<ShopInfo> getShop(String shopId);

  void updatePlan(String shopId, String planId, java.time.Instant expiryDate);

  /** Minimal shop info needed for plan/usage logic. */
  record ShopInfo(String shopId, String planId, java.time.Instant expiryDate) {}
}
