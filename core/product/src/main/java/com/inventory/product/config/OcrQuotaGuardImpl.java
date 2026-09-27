package com.inventory.product.config;

import com.inventory.ocr.service.OcrQuotaGuard;
import com.inventory.plan.service.EntitlementGuard;
import com.inventory.plan.service.OcrMeteringService;
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
  private OcrMeteringService ocrMeteringService;

  @Override
  public void requireUnit(String shopId) {
    entitlementGuard.requireOcrUnit(shopId);
  }

  @Override
  public void recordUnit(String shopId) {
    ocrMeteringService.recordUnit(shopId);
  }
}
