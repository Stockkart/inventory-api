package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.entitlement.PlanFeature;
import com.inventory.common.exception.EntitlementException;
import com.inventory.plan.domain.model.EntitlementEnforcementMode;
import com.inventory.plan.domain.model.EntitlementSource;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.ShopEntitlements;
import com.inventory.plan.domain.model.Usage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementGuardTest {

  @Mock
  private EntitlementService entitlementService;

  @Mock
  private UsageService usageService;

  @Mock
  private EffectivePlanResolver effectivePlanResolver;

  @InjectMocks
  private EntitlementGuard guard;

  @Test
  void enforceBlocksMissingFeatureWithUpsellDetails() {
    guard.mode = EntitlementEnforcementMode.ENFORCE;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), null, null));
    Plan pro = EntitlementServiceTest.plan("pro", "PROFESSIONAL", Set.of(PlanFeature.ACCOUNTING), 5, 10);
    when(effectivePlanResolver.activeCatalogue()).thenReturn(List.of(pro));

    assertThatThrownBy(() -> guard.requireFeature("shop-1", PlanFeature.ACCOUNTING))
        .isInstanceOfSatisfying(EntitlementException.class, e -> {
          assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FEATURE_NOT_IN_PLAN);
          assertThat(e.getDetails())
              .containsEntry("feature", "ACCOUNTING")
              .containsEntry("requiredPlanCode", "PROFESSIONAL")
              .containsEntry("currentPlanCode", "STARTER");
        });
  }

  @Test
  void logOnlyAllowsMissingFeature() {
    guard.mode = EntitlementEnforcementMode.LOG_ONLY;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), null, null));

    assertThatCode(() -> guard.requireFeature("shop-1", PlanFeature.ACCOUNTING)).doesNotThrowAnyException();
  }

  @Test
  void offSkipsResolution() {
    guard.mode = EntitlementEnforcementMode.OFF;

    guard.requireFeature("shop-1", PlanFeature.ACCOUNTING);
    guard.requireSeat("shop-1");
    guard.requireOcrUnit("shop-1");

    verifyNoInteractions(entitlementService);
  }

  @Test
  void seatLimitBlocksAtLimit() {
    guard.mode = EntitlementEnforcementMode.ENFORCE;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), 2, null));
    when(usageService.getUserCountForShop("shop-1")).thenReturn(2);

    assertThatThrownBy(() -> guard.requireSeat("shop-1"))
        .isInstanceOfSatisfying(EntitlementException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SEAT_LIMIT_REACHED));
  }

  @Test
  void seatBelowLimitPasses() {
    guard.mode = EntitlementEnforcementMode.ENFORCE;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), 2, null));
    when(usageService.getUserCountForShop("shop-1")).thenReturn(1);

    assertThatCode(() -> guard.requireSeat("shop-1")).doesNotThrowAnyException();
  }

  @Test
  void ocrQuotaBlocksWhenMonthlyIncludedIsUsed() {
    guard.mode = EntitlementEnforcementMode.ENFORCE;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), null, 3));
    Usage usage = new Usage();
    usage.setOcrUsed(3);
    when(usageService.getOrCreateCurrentMonthUsage("shop-1")).thenReturn(usage);

    assertThatThrownBy(() -> guard.requireOcrUnit("shop-1"))
        .isInstanceOfSatisfying(EntitlementException.class,
            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OCR_QUOTA_EXCEEDED));
  }

  @Test
  void nullLimitsAreUnlimited() {
    guard.mode = EntitlementEnforcementMode.ENFORCE;
    when(entitlementService.resolve("shop-1")).thenReturn(starter(Set.of(), null, null));

    assertThatCode(() -> {
      guard.requireSeat("shop-1");
      guard.requireOcrUnit("shop-1");
    }).doesNotThrowAnyException();
  }

  private static ShopEntitlements starter(Set<PlanFeature> features, Integer userLimit, Integer ocrLimit) {
    return new ShopEntitlements("shop-1", "starter", "STARTER", EntitlementSource.TRIAL,
        features, userLimit, ocrLimit, null);
  }
}
