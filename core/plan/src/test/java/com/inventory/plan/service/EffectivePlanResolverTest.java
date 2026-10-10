package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.repository.PlanRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EffectivePlanResolverTest {

  @Mock
  private PlanRepository planRepository;

  @InjectMocks
  private EffectivePlanResolver resolver;

  @Test
  void trialPlanPrefersStarter() {
    Plan starter = plan("starter-id", "Starter", "STARTER", 1, null, null);
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.of(starter));

    assertThat(resolver.trialPlan()).isSameAs(starter);
    verify(planRepository, never()).findByPlanName("Base");
  }

  @Test
  void trialPlanFallsBackToLegacyBaseBeforeSeeding() {
    Plan base = plan("base-id", "Base", null, null, null, null);
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.empty());
    when(planRepository.findByPlanName("Base")).thenReturn(Optional.of(base));

    assertThat(resolver.trialPlan()).isSameAs(base);
  }

  @Test
  void trialPlanThrowsWhenNeitherExists() {
    when(planRepository.findByCode("STARTER")).thenReturn(Optional.empty());
    when(planRepository.findByPlanName("Base")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.trialPlan()).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void resolveUsesShopPlanWhenPresent() {
    Plan silver = plan("silver-id", "Silver", null, null, null, null);
    when(planRepository.findById("silver-id")).thenReturn(Optional.of(silver));

    assertThat(resolver.resolve("silver-id")).isSameAs(silver);
  }

  @Test
  void resolveThrowsForUnknownPlanIdInsteadOfFallingBack() {
    when(planRepository.findById("gone")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> resolver.resolve("gone")).isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void activeCatalogueHidesDeactivatedAndOrdersByDisplayOrderThenLegacyPrice() {
    Plan base = plan("b", "Base", null, null, true, new BigDecimal("500"));
    Plan freeLegacy = plan("f", "Free", null, null, null, null);
    Plan retired = plan("r", "Silver", null, null, false, new BigDecimal("100"));
    Plan enterprise = plan("e", "Enterprise", "ENTERPRISE", 3, true, BigDecimal.ZERO);
    Plan starter = plan("s", "Starter", "STARTER", 1, true, BigDecimal.ZERO);
    when(planRepository.findAll()).thenReturn(List.of(base, freeLegacy, retired, enterprise, starter));

    assertThat(resolver.activeCatalogue())
        .extracting(Plan::getId)
        .containsExactly("s", "e", "f", "b");
  }

  private static Plan plan(String id, String name, String code, Integer order, Boolean active, BigDecimal price) {
    Plan p = new Plan();
    p.setId(id);
    p.setPlanName(name);
    p.setCode(code);
    p.setDisplayOrder(order);
    p.setActive(active);
    p.setPrice(price);
    return p;
  }
}
