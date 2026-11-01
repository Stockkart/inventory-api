package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.PlanPaymentOrder;
import com.inventory.plan.rest.dto.response.AdminPlanOrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PlanOrderAdminMapper {

  AdminPlanOrderResponse toResponse(PlanPaymentOrder order);
}
