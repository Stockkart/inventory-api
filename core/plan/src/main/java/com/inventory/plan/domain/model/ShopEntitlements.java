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
    Instant expiresAt,
    Set<String> addOnCodes) {

  public ShopEntitlements {
    features = features == null || features.isEmpty()
        ? Set.of()
        : Set.copyOf(EnumSet.copyOf(features));
    addOnCodes = addOnCodes == null ? Set.of() : Set.copyOf(addOnCodes);
  }

  /** Plan entitlements plus live add-ons: features are the union, seats are added (§9). */
  public ShopEntitlements withAddOns(Set<PlanFeature> addOnFeatures, int extraSeats, Set<String> codes) {
    if (codes.isEmpty()) {
      return this;
    }
    Set<PlanFeature> union = EnumSet.noneOf(PlanFeature.class);
    union.addAll(features);
    union.addAll(addOnFeatures);
    return new ShopEntitlements(shopId, planId, planCode, source, union,
        userLimit == null ? null : userLimit + extraSeats, ocrLimit, expiresAt, codes);
  }

  public boolean allows(PlanFeature feature) {
    return source == EntitlementSource.LEGACY_GRANDFATHERED || features.contains(feature);
  }

  /** Features the client should treat as unlocked. */
  public Set<PlanFeature> effectiveFeatures() {
    return source == EntitlementSource.LEGACY_GRANDFATHERED ? EnumSet.allOf(PlanFeature.class) : features;
  }
}
