package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.inventory.plan.api.port.ShopPort;
import com.inventory.plan.api.port.ShopPort.ShopInfo;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanAction;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.validation.PlanValidator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The trial length shown to shops comes from plan.trial-days, not from frontend text. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlanServiceTrialDaysTest {

  @Mock private PlanRepository planRepository;
  @Mock private ShopPort shopProvider;
  @Mock private PlanMapper planMapper;
  @Mock private PlanValidator planValidator;
  @Mock private UsageService usageService;
  @Mock private EffectivePlanResolver effectivePlanResolver;
  @Mock private EntitlementService entitlementService;
  @InjectMocks private PlanService planService;

  @Test
  void everyListedPlanCarriesTheTrialLength() {
    planService.trialDays = 3;
    Plan base = new Plan();
    when(effectivePlanResolver.activeCatalogue()).thenReturn(List.of(base));
    when(planMapper.toResponse(base)).thenReturn(new PlanResponse());

    List<PlanResponse> plans = planService.listPlans();

    assertThat(plans).extracting(PlanResponse::getTrialDays).containsExactly(3);
  }

  @Test
  void theShopPlanStatusCarriesTheTrialLength() {
    planService.trialDays = 7;
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopInfo("s1", null, Instant.now())));
    when(effectivePlanResolver.trialPlan()).thenReturn(new Plan());
    when(usageService.getOrCreateCurrentMonthUsage("s1")).thenReturn(new Usage());
    when(planMapper.toShopPlanStatusResponse(anyString(), any(), any(), any(), anyBoolean(), anyBoolean(),
        anyBoolean(), any(), any(), any(), anyBoolean())).thenReturn(new ShopPlanStatusResponse());

    ShopPlanStatusResponse status = planService.getShopPlanStatus("s1");

    assertThat(status.getTrialDays()).isEqualTo(7);
    assertThat(status.isCanBuyAddOnsAlone()).isFalse();
  }

  @Test
  void aShopOnAnActivePaidPlanCanBuyAddOnsAlone() {
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopInfo("s1", "pro", Instant.now().plusSeconds(86400))));
    when(planRepository.findById("pro")).thenReturn(Optional.of(new Plan()));
    when(usageService.getOrCreateCurrentMonthUsage("s1")).thenReturn(new Usage());
    when(entitlementService.paidTerm("s1")).thenReturn(Optional.of(
        new com.inventory.plan.domain.model.PaidTerm("pro", Instant.now().plusSeconds(86400))));
    when(planMapper.toShopPlanStatusResponse(anyString(), any(), any(), any(), anyBoolean(), anyBoolean(),
        anyBoolean(), any(), any(), any(), anyBoolean())).thenReturn(new ShopPlanStatusResponse());

    assertThat(planService.getShopPlanStatus("s1").isCanBuyAddOnsAlone()).isTrue();
  }

  @Test
  void aShopOnItsTrialCannotStartAnotherTrialAndMayBuyEveryPlan() {
    Plan starter = new Plan();
    starter.setId("p1");
    when(effectivePlanResolver.activeCatalogue()).thenReturn(List.of(starter));
    when(shopProvider.getShop("s1")).thenReturn(Optional.of(new ShopInfo("s1", null, Instant.now())));
    when(effectivePlanResolver.trialPlan()).thenReturn(new Plan());
    when(usageService.getOrCreateCurrentMonthUsage("s1")).thenReturn(new Usage());
    when(planMapper.toShopPlanStatusResponse(anyString(), any(), any(), any(), anyBoolean(), anyBoolean(),
        anyBoolean(), any(), any(), any(), anyBoolean())).thenReturn(new ShopPlanStatusResponse());

    ShopPlanStatusResponse status = planService.getShopPlanStatus("s1");

    assertThat(status.isTrialAvailable()).isFalse();
    assertThat(status.getPlanOptions()).singleElement().satisfies(option -> {
      assertThat(option.getPlanId()).isEqualTo("p1");
      assertThat(option.getAction()).isEqualTo(PlanAction.BUY);
      assertThat(option.isActionAvailable()).isTrue();
    });
  }
}
