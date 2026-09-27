package com.inventory.plan.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.EntitlementException;
import com.inventory.plan.domain.model.EntitlementEnforcementMode;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.ShopEntitlements;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.response.ShopEntitlementsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Checks a shop's entitlements before an action and applies the configured enforcement mode.
 */
@Service
@Slf4j
public class EntitlementGuard {

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private UsageService usageService;

  @Autowired
  private ShopAddOnService shopAddOnService;

  @Autowired
  private EffectivePlanResolver effectivePlanResolver;

  @Value("${plan.entitlements.enforcement:LOG_ONLY}")
  EntitlementEnforcementMode mode = EntitlementEnforcementMode.LOG_ONLY;

  public EntitlementEnforcementMode mode() {
    return mode;
  }

  public void requireFeature(String shopId, PlanFeature feature) {
    if (mode == EntitlementEnforcementMode.OFF) {
      return;
    }
    ShopEntitlements entitlements = entitlementService.resolve(shopId);
    if (entitlements.allows(feature)) {
      return;
    }
    Map<String, Object> details = baseDetails(entitlements);
    details.put("feature", feature.name());
    cheapestPlanWith(feature).ifPresent(plan -> details.put("requiredPlanCode", plan.getCode()));
    deny(shopId, new EntitlementException(ErrorCode.FEATURE_NOT_IN_PLAN,
        "Your plan does not include " + feature.name() + ". Upgrade to unlock it.", details));
  }

  /** Called before a new member is invited or admitted. */
  public void requireSeat(String shopId) {
    if (mode == EntitlementEnforcementMode.OFF) {
      return;
    }
    ShopEntitlements entitlements = entitlementService.resolve(shopId);
    Integer limit = entitlements.userLimit();
    if (limit == null) {
      return;
    }
    int members = usageService.getUserCountForShop(shopId);
    if (members < limit) {
      return;
    }
    Map<String, Object> details = baseDetails(entitlements);
    details.put("userLimit", limit);
    details.put("userCount", members);
    deny(shopId, new EntitlementException(ErrorCode.SEAT_LIMIT_REACHED,
        "Your plan allows " + limit + " user(s). Upgrade or add a user seat to invite more.", details));
  }

  /** One unit is one successfully processed invoice, however many pages it has. */
  public void requireOcrUnit(String shopId) {
    if (mode == EntitlementEnforcementMode.OFF) {
      return;
    }
    ShopEntitlements entitlements = entitlementService.resolve(shopId);
    Integer limit = entitlements.ocrLimit();
    if (limit == null) {
      return;
    }
    Usage usage = usageService.getOrCreateCurrentMonthUsage(shopId);
    int used = usage.getOcrUsed() != null ? usage.getOcrUsed() : 0;
    if (used < limit || shopAddOnService.ocrCreditsRemaining(shopId) > 0) {
      return;
    }
    Map<String, Object> details = baseDetails(entitlements);
    details.put("ocrLimit", limit);
    details.put("ocrUsed", used);
    details.put("ocrTopUpRemaining", 0);
    deny(shopId, new EntitlementException(ErrorCode.OCR_QUOTA_EXCEEDED,
        "You have used all " + limit + " invoice scans for this month. Buy a scan top-up or upgrade to scan more.",
        details));
  }

  public ShopEntitlementsResponse describe(String shopId) {
    ShopEntitlements entitlements = entitlementService.resolve(shopId);
    Usage usage = usageService.getOrCreateCurrentMonthUsage(shopId);
    return ShopEntitlementsResponse.builder()
        .planId(entitlements.planId())
        .planCode(entitlements.planCode())
        .source(entitlements.source())
        .features(entitlements.effectiveFeatures().stream().sorted().toList())
        .enforcement(mode)
        .userLimit(entitlements.userLimit())
        .userCount(usageService.getUserCountForShop(shopId))
        .ocrLimit(entitlements.ocrLimit())
        .ocrUsed(usage.getOcrUsed() != null ? usage.getOcrUsed() : 0)
        .ocrTopUpRemaining(shopAddOnService.ocrCreditsRemaining(shopId))
        .addOns(entitlements.addOnCodes().stream().sorted().toList())
        .expiresAt(entitlements.expiresAt())
        .build();
  }

  private void deny(String shopId, EntitlementException denial) {
    if (mode == EntitlementEnforcementMode.ENFORCE) {
      throw denial;
    }
    log.warn("Entitlement check would block shop {}: {} {}", shopId, denial.getErrorCode(), denial.getDetails());
  }

  private Optional<Plan> cheapestPlanWith(PlanFeature feature) {
    return effectivePlanResolver.activeCatalogue().stream()
        .filter(plan -> plan.getCode() != null)
        .filter(plan -> plan.getFeatures() != null && plan.getFeatures().contains(feature))
        .findFirst();
  }

  private static Map<String, Object> baseDetails(ShopEntitlements entitlements) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("source", entitlements.source().name());
    if (entitlements.planCode() != null) {
      details.put("currentPlanCode", entitlements.planCode());
    }
    return details;
  }
}
