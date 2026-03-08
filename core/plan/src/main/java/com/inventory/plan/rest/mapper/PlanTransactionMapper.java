package com.inventory.plan.rest.mapper;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.PlanTransaction;
import com.inventory.plan.rest.dto.plan.AssignPlanRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface PlanTransactionMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "planId", source = "plan.id")
  @Mapping(target = "planName", source = "plan.planName")
  @Mapping(target = "amount", source = "plan", qualifiedByName = "planAmount")
  @Mapping(target = "durationMonths", source = "request", qualifiedByName = "durationMonths")
  @Mapping(target = "paymentMethod", source = "request", qualifiedByName = "paymentMethod")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  PlanTransaction toTransaction(String shopId, Plan plan, AssignPlanRequest request);

  @Named("planAmount")
  default BigDecimal planAmount(Plan plan) {
    return plan.getArcPrice() != null ? plan.getArcPrice() : plan.getPrice();
  }

  @Named("durationMonths")
  default int durationMonths(AssignPlanRequest request) {
    return request.getDurationMonths() != null ? request.getDurationMonths() : 1;
  }

  @Named("paymentMethod")
  default String paymentMethod(AssignPlanRequest request) {
    return request.getPaymentMethod() != null ? request.getPaymentMethod() : "CARD";
  }
}
