package com.inventory.product.service.api;

import com.inventory.ocr.api.port.OcrQuotaGuard;
import com.inventory.plan.api.Entitlements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Adapter implementation for the ocr module. Delegates quota checks to the plan api.
 */
@Component
public class OcrQuotaGuardImpl implements OcrQuotaGuard {

  @Autowired
  private Entitlements entitlements;

  @Override
  public void requireUnit(String shopId) {
    entitlements.requireOcrUnit(shopId);
  }

  @Override
  public void recordUnit(String shopId) {
    entitlements.recordOcrUnit(shopId);
  }
}
