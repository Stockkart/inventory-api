package com.inventory.plan.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.plan.domain.model.EntitlementSource;
import com.inventory.plan.domain.model.ShopEntitlements;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OcrMeteringServiceTest {

  @Mock private EntitlementService entitlementService;
  @Mock private UsageService usageService;
  @Mock private ShopAddOnService shopAddOnService;

  @InjectMocks
  private OcrMeteringService metering;

  @Test
  void usesTheMonthlyQuotaBeforeCredits() {
    when(entitlementService.resolve("shop-1")).thenReturn(entitlements(100));
    when(usageService.recordIncludedOcrUnit("shop-1", 100)).thenReturn(true);

    metering.recordUnit("shop-1");

    verify(shopAddOnService, never()).consumeOcrCredit("shop-1");
  }

  @Test
  void spendsACreditOnceTheMonthlyQuotaIsUsed() {
    when(entitlementService.resolve("shop-1")).thenReturn(entitlements(100));
    when(usageService.recordIncludedOcrUnit("shop-1", 100)).thenReturn(false);
    when(shopAddOnService.consumeOcrCredit("shop-1")).thenReturn(true);

    metering.recordUnit("shop-1");

    verify(usageService, never()).recordOcrUsage("shop-1");
  }

  @Test
  void countsAnOverQuotaScanWhenNothingIsLeft() {
    when(entitlementService.resolve("shop-1")).thenReturn(entitlements(100));
    when(usageService.recordIncludedOcrUnit("shop-1", 100)).thenReturn(false);
    when(shopAddOnService.consumeOcrCredit("shop-1")).thenReturn(false);

    metering.recordUnit("shop-1");

    verify(usageService).recordOcrUsage("shop-1");
  }

  @Test
  void unlimitedPlansJustCount() {
    when(entitlementService.resolve("shop-1")).thenReturn(entitlements(null));

    metering.recordUnit("shop-1");

    verify(usageService).recordOcrUsage("shop-1");
    verify(shopAddOnService, never()).consumeOcrCredit("shop-1");
  }

  private static ShopEntitlements entitlements(Integer ocrLimit) {
    return new ShopEntitlements("shop-1", "p", "PROFESSIONAL", EntitlementSource.PLAN, Set.of(), 3, ocrLimit, null, Set.of());
  }
}
