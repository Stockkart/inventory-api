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

  /** Minimal shop info needed for plan/usage logic. */
  record ShopInfo(String shopId, String planId, Instant planExpiryDate) {}
}
