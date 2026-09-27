package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.request.PaymentWebhookPayload;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import com.inventory.plan.rest.dto.response.AdminPlanResponse;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.utils.constants.PlanConstants;
import com.inventory.plan.validation.PlanValidator.LimitReachedResult;
import com.inventory.plan.utils.PlanUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.time.Instant;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE, imports = PlanUtils.class)
public interface PlanMapper {

  @Mapping(target = "listPrice", expression = "java(PlanUtils.listPrice(plan))")
  PlanResponse toResponse(Plan plan);

  @Mapping(target = "listPrice", expression = "java(PlanUtils.listPrice(plan))")
  @Mapping(target = "active", expression = "java(!Boolean.FALSE.equals(plan.getActive()))")
  AdminPlanResponse toAdminResponse(Plan plan);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "active", constant = "true")
  Plan toEntity(PlanAdminRequest request);

  UsageResponse toUsageResponse(Usage usage);

  default AssignPlanRequest toAssignPlanRequest(PaymentWebhookPayload payload) {
    if (payload == null) return null;
    AssignPlanRequest req = new AssignPlanRequest();
    req.setPlanId(payload.getPlanId());
    req.setDurationMonths(payload.getDurationMonths() != null ? payload.getDurationMonths() : PlanConstants.WEBHOOK_DEFAULT_DURATION_MONTHS);
    req.setPaymentMethod(payload.getPaymentMethod() != null ? payload.getPaymentMethod() : PlanConstants.DEFAULT_PAYMENT_METHOD);
    return req;
  }

  default ShopPlanStatusResponse toShopPlanStatusResponse(
      String shopId,
      String planId,
      PlanResponse plan,
      Instant planExpiryDate,
      boolean trial,
      boolean trialExpired,
      boolean planExpired,
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
        .planExpired(planExpired)
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
