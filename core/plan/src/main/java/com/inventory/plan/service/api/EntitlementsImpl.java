package com.inventory.plan.service.api;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.api.Entitlements;
import com.inventory.plan.service.EntitlementGuard;
import com.inventory.plan.service.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EntitlementsImpl implements Entitlements {

  @Autowired
  private EntitlementGuard entitlementGuard;

  @Autowired
  private UsageService usageService;

  @Override
  public void requireFeature(String shopId, PlanFeature feature) {
    entitlementGuard.requireFeature(shopId, feature);
  }

  @Override
  public void requireOcrUnit(String shopId) {
    entitlementGuard.requireOcrUnit(shopId);
  }

  @Override
  public void recordOcrUnit(String shopId) {
    usageService.recordOcrUsage(shopId);
  }
}
