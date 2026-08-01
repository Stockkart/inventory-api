package com.inventory.plan.service;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanKind;
import com.inventory.plan.domain.repository.PlanRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards the fallback chain that replaced the lookup for a plan named "Base".
 *
 * <p>The old code threw when that row was absent, which 500'd the plan status endpoint for every
 * trial shop. Every case here must resolve to something.
 */
class EffectivePlanResolverTest {

  private PlanRepository planRepository;
  private EffectivePlanResolver resolver;

  @BeforeEach
  void setUp() {
    planRepository = mock(PlanRepository.class);
    resolver = new EffectivePlanResolver();
    ReflectionTestUtils.setField(resolver, "planRepository", planRepository);
  }

  private Plan plan(String id, String name, Integer rank, Integer price, PlanKind kind) {
    Plan p = new Plan();
    p.setId(id);
    p.setPlanName(name);
    p.setTierRank(rank);
    p.setArcPrice(price == null ? null : BigDecimal.valueOf(price));
    p.setKind(kind);
    return p;
  }

  private List<Plan> ladder() {
    List<Plan> plans = new ArrayList<>();
    plans.add(plan("3", "Enterprise", 3, 9999, PlanKind.PLAN));
    plans.add(plan("1", "Starter", 1, 4999, PlanKind.PLAN));
    plans.add(plan("2", "Professional", 2, 6999, PlanKind.PLAN));
    plans.add(plan("a", "Additional User", null, 500, PlanKind.ADDON));
    plans.add(plan("o", "OCR 500 Invoices", null, 199, PlanKind.OCR_TOPUP));
    return plans;
  }

  @Test
  void entryPlanIsTheLowestRankedTier() {
    when(planRepository.findAll()).thenReturn(ladder());
    assertEquals("Starter", resolver.entryPlan().getPlanName());
  }

  @Test
  void entryPlanIgnoresAddonsAndTopupsEvenWhenCheaper() {
    when(planRepository.findAll()).thenReturn(ladder());
    // The ₹199 OCR pack is the cheapest row but is not a subscription tier.
    assertEquals("Starter", resolver.entryPlan().getPlanName());
  }

  @Test
  void unrankedLegacyPlansSortAfterRankedTiers() {
    List<Plan> plans = ladder();
    // A legacy row with no tierRank must not masquerade as the entry tier.
    plans.add(plan("legacy", "Standard", null, 4200, PlanKind.PLAN));
    when(planRepository.findAll()).thenReturn(plans);

    assertEquals("Starter", resolver.entryPlan().getPlanName());
  }

  @Test
  void emptyCatalogYieldsAPermissivePlaceholderRatherThanThrowing() {
    when(planRepository.findAll()).thenReturn(List.of());

    Plan fallback = resolver.entryPlan();
    assertEquals(EffectivePlanResolver.UNRESOLVED_PLAN_NAME, fallback.getPlanName());
    assertTrue(fallback.isUnlimited(), "placeholder must not report limits as reached");
  }

  @Test
  void catalogOfOnlyAddonsYieldsThePlaceholder() {
    when(planRepository.findAll())
        .thenReturn(List.of(plan("a", "Additional User", null, 500, PlanKind.ADDON)));

    assertEquals(EffectivePlanResolver.UNRESOLVED_PLAN_NAME, resolver.entryPlan().getPlanName());
  }

  @Test
  void forShopReturnsTheStoredPlan() {
    Plan pro = plan("2", "Professional", 2, 6999, PlanKind.PLAN);
    when(planRepository.findById("2")).thenReturn(Optional.of(pro));

    assertEquals("Professional", resolver.forShop("2").getPlanName());
  }

  @Test
  void forShopFallsBackToEntryTierForATrialShop() {
    when(planRepository.findAll()).thenReturn(ladder());

    assertEquals("Starter", resolver.forShop(null).getPlanName());
    assertEquals("Starter", resolver.forShop("").getPlanName());
    assertEquals("Starter", resolver.forShop("   ").getPlanName());
  }

  @Test
  void forShopFallsBackWhenTheReferencedPlanWasDeleted() {
    // A deleted plan must not lock the shop out of its own status page.
    when(planRepository.findById(anyString())).thenReturn(Optional.empty());
    when(planRepository.findAll()).thenReturn(ladder());

    assertEquals("Starter", resolver.forShop("69a86d8c3933c8737397dca7").getPlanName());
  }
}
