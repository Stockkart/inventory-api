package com.inventory.product.config;

import com.inventory.ocr.service.OcrQuotaGuard;
import com.inventory.plan.service.EntitlementGuard;
import com.inventory.plan.service.UsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter implementation for the ocr module. Delegates quota checks to the plan module.
 */
@Component
public class OcrQuotaGuardImpl implements OcrQuotaGuard {

  @Autowired
  private EntitlementGuard entitlementGuard;

  @Autowired
  private UsageService usageService;

  @Override
  public void requireUnit(String shopId) {
    entitlementGuard.requireOcrUnit(shopId);
  }

  @Override
  public void recordUnit(String shopId) {
    usageService.recordOcrUsage(shopId);
  }
}
