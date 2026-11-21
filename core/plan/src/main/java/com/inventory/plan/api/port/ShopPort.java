package com.inventory.plan.api.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Shop data the plan module needs. Implemented by the product module, which owns shops, so plan
 * does not depend on it.
 */
public interface ShopPort {

  Optional<ShopInfo> getShop(String shopId);

  void updatePlan(String shopId, String planId, Instant expiryDate);

  /** Visits every shop, page by page. */
  void forEachShop(java.util.function.Consumer<ShopInfo> action);

  /** The shop that owns a referral code; the code must already be normalised. */
  Optional<ReferralShop> findByReferralCode(String referralCode);

  Optional<ReferralShop> getReferralShop(String shopId);

  /** Referral info of several shops in one read; unknown ids are left out. */
  java.util.List<ReferralShop> referralShops(java.util.Collection<String> shopIds);

  /** Minimal shop info needed for plan/usage logic. */
  record ShopInfo(String shopId, String planId, Instant planExpiryDate) {}

  /** What referral logic needs about a shop: its code, and contacts for self-referral checks. */
  record ReferralShop(String shopId, String name, String referralCode, String contactEmail, String contactPhone) {}
}
