package com.inventory.plan.service;

import com.inventory.plan.domain.model.ShopEntitlements;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Records one successfully processed invoice: the month's included quota first, then purchased
 * top-up credits, never the reverse (§23).
 */
@Service
@Slf4j
public class OcrMeteringService {

  @Autowired
  private EntitlementService entitlementService;

  @Autowired
  private UsageService usageService;

  @Autowired
  private ShopAddOnService shopAddOnService;

  public void recordUnit(String shopId) {
    ShopEntitlements entitlements = entitlementService.resolve(shopId);
    Integer limit = entitlements.ocrLimit();
    if (limit == null) {
      usageService.recordOcrUsage(shopId);
      return;
    }
    if (usageService.recordIncludedOcrUnit(shopId, limit)) {
      return;
    }
    if (shopAddOnService.consumeOcrCredit(shopId)) {
      return;
    }
    // Only reachable when enforcement lets an over-quota scan through; still count it.
    log.warn("Shop {} scanned an invoice with no included quota or credits left", shopId);
    usageService.recordOcrUsage(shopId);
  }
}
