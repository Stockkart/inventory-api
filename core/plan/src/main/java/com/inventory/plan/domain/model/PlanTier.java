package com.inventory.plan.domain.model;

/**
 * Subscription tiers, cheapest first.
 *
 * <p>Ordering comparisons should use {@code Plan.tierRank} rather than the enum ordinal, so a tier
 * can be inserted into the ladder without a code change.
 */
public enum PlanTier {
  STARTER,
  PROFESSIONAL,
  ENTERPRISE
}
