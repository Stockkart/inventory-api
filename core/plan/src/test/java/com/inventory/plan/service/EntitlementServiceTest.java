package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.entitlement.PlanFeature;
import com.inventory.plan.domain.model.EntitlementSource;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.ShopEntitlements;
import com.inventory.plan.domain.model.ShopSubscription;
import com.inventory.plan.domain.model.SubscriptionStatus;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.ShopSubscriptionRepository;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntitlementServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-27T00:00:00Z");

  @Mock
  private ShopSubscriptionRepository shopSubscriptionRepository;

  @Mock
  private ShopProvider shopProvider;

  @Mock
  private PlanRepository planRepository;

  @Mock
  private EffectivePlanResolver effectivePlanResolver;

  @InjectMocks
  private EntitlementService service;

  @BeforeEach
  void fixClock() {
    service.clock = Clock.fixed(NOW, ZoneOffset.UTC);
  }

  @Test
  void activeSubscriptionGetsItsPlanFeaturesAndLimits() {
    subscription("pro", SubscriptionStatus.ACTIVE, NOW.plusSeconds(3600));
    when(planRepository.findById("pro")).thenReturn(Optional.of(
        plan("pro", "PROFESSIONAL", Set.of(PlanFeature.ACCOUNTING), 5, 1000)));

    ShopEntitlements result = service.resolve("shop-1");

    assertThat(result.source()).isEqualTo(EntitlementSource.PLAN);
    assertThat(result.planCode()).isEqualTo("PROFESSIONAL");
    assertThat(result.allows(PlanFeature.ACCOUNTING)).isTrue();
    assertThat(result.allows(PlanFeature.SALARY)).isFalse();
    assertThat(result.userLimit()).isEqualTo(5);
    assertThat(result.ocrLimit()).isEqualTo(1000);
  }

  @Test
  void expiredSubscriptionFallsBackToTrialSetNotEmpty() {
    subscription("pro", SubscriptionStatus.ACTIVE, NOW.minusSeconds(1));
    when(effectivePlanResolver.findTrialPlan()).thenReturn(Optional.of(
        plan("starter", "STARTER", Set.of(PlanFeature.BARCODE_GENERATOR), 1, 50)));

    ShopEntitlements result = service.resolve("shop-1");

    assertThat(result.source()).isEqualTo(EntitlementSource.TRIAL);
    assertThat(result.features()).containsExactly(PlanFeature.BARCODE_GENERATOR);
  }

  @Test
  void legacyPlanWithoutCodeIsGrandfatheredWithEveryFeatureAndNoLimits() {
    subscription("silver", SubscriptionStatus.ACTIVE, NOW.plusSeconds(3600));
    when(planRepository.findById("silver")).thenReturn(Optional.of(plan("silver", null, Set.of(), 2, null)));

    ShopEntitlements result = service.resolve("shop-1");

    assertThat(result.source()).isEqualTo(EntitlementSource.LEGACY_GRANDFATHERED);
    assertThat(result.effectiveFeatures()).containsExactlyInAnyOrder(PlanFeature.values());
    assertThat(result.userLimit()).isNull();
  }

  @Test
  void unlimitedPlanHasNoLimits() {
    subscription("ent", SubscriptionStatus.ACTIVE, NOW.plusSeconds(3600));
    Plan enterprise = plan("ent", "ENTERPRISE", Set.of(), 10, 100);
    enterprise.setUnlimited(true);
    when(planRepository.findById("ent")).thenReturn(Optional.of(enterprise));

    ShopEntitlements result = service.resolve("shop-1");

    assertThat(result.userLimit()).isNull();
    assertThat(result.ocrLimit()).isNull();
  }

  @Test
  void missingSubscriptionReadsShopPlanFields() {
    when(shopSubscriptionRepository.findById("shop-1")).thenReturn(Optional.empty());
    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", "pro", NOW.plusSeconds(60))));
    when(planRepository.findById("pro")).thenReturn(Optional.of(plan("pro", "PROFESSIONAL", Set.of(), 5, 10)));

    assertThat(service.resolve("shop-1").source()).isEqualTo(EntitlementSource.PLAN);
  }

  @Test
  void noTrialPlanYieldsEmptyTrialSetInsteadOfThrowing() {
    subscription(null, SubscriptionStatus.TRIAL, NOW.plusSeconds(60));
    when(effectivePlanResolver.findTrialPlan()).thenReturn(Optional.empty());

    ShopEntitlements result = service.resolve("shop-1");

    assertThat(result.source()).isEqualTo(EntitlementSource.TRIAL);
    assertThat(result.features()).isEmpty();
  }

  @Test
  void cachesUntilTtlOrInvalidation() {
    subscription("pro", SubscriptionStatus.ACTIVE, NOW.plusSeconds(3600));
    when(planRepository.findById("pro")).thenReturn(Optional.of(plan("pro", "PROFESSIONAL", Set.of(), 5, 10)));

    service.resolve("shop-1");
    service.resolve("shop-1");
    verify(shopSubscriptionRepository, times(1)).findById("shop-1");

    service.invalidate("shop-1");
    service.resolve("shop-1");
    verify(shopSubscriptionRepository, times(2)).findById("shop-1");

    service.clock = Clock.fixed(NOW.plusSeconds(service.cacheTtlSeconds), ZoneOffset.UTC);
    service.resolve("shop-1");
    verify(shopSubscriptionRepository, times(3)).findById("shop-1");
  }

  private void subscription(String planId, SubscriptionStatus status, Instant expiresAt) {
    when(shopSubscriptionRepository.findById("shop-1")).thenReturn(Optional.of(ShopSubscription.builder()
        .id("shop-1").shopId("shop-1").planId(planId).status(status).expiresAt(expiresAt).build()));
  }

  static Plan plan(String id, String code, Set<PlanFeature> features, Integer userLimit, Integer ocrLimit) {
    Plan plan = new Plan();
    plan.setId(id);
    plan.setCode(code);
    plan.setFeatures(features);
    plan.setUserLimit(userLimit);
    plan.setOcrLimit(ocrLimit);
    return plan;
  }
}
