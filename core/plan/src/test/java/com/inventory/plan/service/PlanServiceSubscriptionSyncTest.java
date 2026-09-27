package com.inventory.plan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.metrics.MetricsWrapper;
import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.domain.repository.PlanRepository;
import com.inventory.plan.domain.repository.PlanTransactionRepository;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.mapper.PlanTransactionMapper;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.service.ShopProvider.ShopInfo;
import com.inventory.plan.validation.PlanValidator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PlanServiceSubscriptionSyncTest {

  @Mock private PlanRepository planRepository;
  @Mock private ShopProvider shopProvider;
  @Mock private PlanTransactionRepository planTransactionRepository;
  @Mock private PlanMapper planMapper;
  @Mock private PlanTransactionMapper planTransactionMapper;
  @Mock private PlanValidator planValidator;
  @Mock private UsageService usageService;
  @Mock private ShopSubscriptionService shopSubscriptionService;
  @Mock private MetricsWrapper metrics;

  @InjectMocks
  private PlanService planService;

  @Test
  void assignPlanSyncsSubscriptionWithTheOrderAndSurvivesSyncFailure() {
    Plan plan = new Plan();
    plan.setId("plan-1");
    AssignPlanRequest request = new AssignPlanRequest();
    request.setPlanId("plan-1");
    request.setDurationMonths(12);
    request.setPaymentOrderId("order-1");
    PlanTransaction tx = new PlanTransaction();
    PlanResponse response = new PlanResponse();

    when(shopProvider.getShop("shop-1")).thenReturn(Optional.of(new ShopInfo("shop-1", null, null)));
    when(planRepository.findById("plan-1")).thenReturn(Optional.of(plan));
    when(shopSubscriptionService.sync(any(), eq("order-1"))).thenThrow(new IllegalStateException("mongo down"));
    when(planTransactionMapper.toTransaction("shop-1", plan, request)).thenReturn(tx);
    when(planMapper.toResponse(plan)).thenReturn(response);

    assertThat(planService.assignPlan("shop-1", request)).isSameAs(response);

    ArgumentCaptor<ShopInfo> synced = ArgumentCaptor.forClass(ShopInfo.class);
    verify(shopSubscriptionService).sync(synced.capture(), eq("order-1"));
    assertThat(synced.getValue().shopId()).isEqualTo("shop-1");
    assertThat(synced.getValue().planId()).isEqualTo("plan-1");
    assertThat(synced.getValue().planExpiryDate()).isNotNull();
    verify(planTransactionRepository).save(tx);
  }
}
