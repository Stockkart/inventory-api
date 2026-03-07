package com.inventory.plan.rest.mapper;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.plan.PlanResponse;
import com.inventory.plan.rest.dto.plan.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.plan.UsageResponse;
import com.inventory.plan.validation.PlanValidator.LimitReachedResult;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PlanMapper {

  PlanResponse toResponse(Plan plan);

  UsageResponse toUsageResponse(Usage usage);

  default ShopPlanStatusResponse toShopPlanStatusResponse(
      String shopId,
      String planId,
      PlanResponse plan,
      Instant planExpiryDate,
      boolean trial,
      boolean trialExpired,
      UsageResponse currentUsage,
      PlanResponse suggestedPlan,
      LimitReachedResult limits,
      boolean userLimitReached) {
    return ShopPlanStatusResponse.builder()
        .shopId(shopId)
        .planId(planId)
        .plan(plan)
        .planExpiryDate(planExpiryDate)
        .trial(trial)
        .trialExpired(trialExpired)
        .currentUsage(currentUsage)
        .suggestedPlan(suggestedPlan)
        .billingLimitReached(limits.billingLimitReached())
        .billCountLimitReached(limits.billCountLimitReached())
        .smsLimitReached(limits.smsLimitReached())
        .whatsappLimitReached(limits.whatsappLimitReached())
        .userLimitReached(userLimitReached)
        .build();
  }
}
