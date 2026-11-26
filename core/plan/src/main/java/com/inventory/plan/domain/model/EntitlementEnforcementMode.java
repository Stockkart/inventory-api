package com.inventory.plan.domain.model;

/**
 * How an entitlement miss is handled. Rollout starts at {@link #LOG_ONLY}.
 */
public enum EntitlementEnforcementMode {
  /** No checks. */
  OFF,
  /** Log what would have been blocked, allow the action. */
  LOG_ONLY,
  /** Block the action with a typed 402. */
  ENFORCE
}
