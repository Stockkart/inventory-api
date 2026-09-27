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

  /** The shop that owns a referral code; the code must already be normalised. */
  default Optional<ReferralShop> findByReferralCode(String referralCode) {
    return Optional.empty();
  }

  default Optional<ReferralShop> getReferralShop(String shopId) {
    return Optional.empty();
  }

  /** Minimal shop info needed for plan/usage logic. */
  record ShopInfo(String shopId, String planId, java.time.Instant planExpiryDate) {}

  /** What referral logic needs about a shop: its code, and contacts for self-referral checks. */
  record ReferralShop(String shopId, String name, String referralCode, String contactEmail, String contactPhone) {}
}
