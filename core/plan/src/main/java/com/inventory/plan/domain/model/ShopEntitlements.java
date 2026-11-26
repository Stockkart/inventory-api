package com.inventory.plan.domain.model;

import com.inventory.common.entitlement.PlanFeature;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * What a shop may use right now. A null limit means no limit.
 */
public record ShopEntitlements(
    String shopId,
    String planId,
    String planCode,
    EntitlementSource source,
    Set<PlanFeature> features,
    Integer userLimit,
    Integer ocrLimit,
    Instant expiresAt) {

  public ShopEntitlements {
    features = features == null || features.isEmpty()
        ? Set.of()
        : Set.copyOf(EnumSet.copyOf(features));
  }

  public boolean allows(PlanFeature feature) {
    return source == EntitlementSource.LEGACY_GRANDFATHERED || features.contains(feature);
  }

  /** Features the client should treat as unlocked. */
  public Set<PlanFeature> effectiveFeatures() {
    return source == EntitlementSource.LEGACY_GRANDFATHERED ? EnumSet.allOf(PlanFeature.class) : features;
  }
}
