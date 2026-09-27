package com.inventory.plan.domain.model;

/**
 * Why a shop has the entitlements it has.
 */
public enum EntitlementSource {
  /** Active, unexpired paid subscription on a catalogue plan. */
  PLAN,
  /** Trial, expired, cancelled, or no subscription: the trial plan's set. */
  TRIAL,
  /** Plan predates the catalogue (no code). Keeps every feature and no new limits until migrated. */
  LEGACY_GRANDFATHERED
}
