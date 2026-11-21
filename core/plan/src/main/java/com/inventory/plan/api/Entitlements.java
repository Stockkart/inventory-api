package com.inventory.plan.api;

import com.inventory.common.entitlement.PlanFeature;

/** Plan entitlement checks other modules and the request pipeline may run. */
public interface Entitlements {

  /** Throws (in ENFORCE mode) when the shop's plan does not grant {@code feature}. */
  void requireFeature(String shopId, PlanFeature feature);

  /** Throws (in ENFORCE mode) when the shop has no OCR units left this period. */
  void requireOcrUnit(String shopId);

  /** Counts one processed invoice against the shop's OCR quota. */
  void recordOcrUnit(String shopId);
}
