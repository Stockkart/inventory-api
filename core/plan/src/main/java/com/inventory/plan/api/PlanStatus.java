package com.inventory.plan.api;

/** Whether a shop may still use the app under its plan or trial. */
public interface PlanStatus {

  /** True only when the shop exists and its plan expiry date has passed. */
  boolean isPlanExpired(String shopId);
}
