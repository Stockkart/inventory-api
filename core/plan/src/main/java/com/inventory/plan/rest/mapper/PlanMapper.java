package com.inventory.plan.rest.mapper;

import com.inventory.plan.domain.model.Plan;
import com.inventory.plan.domain.model.Usage;
import com.inventory.plan.rest.dto.plan.PlanResponse;
import com.inventory.plan.rest.dto.plan.UsageResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PlanMapper {

  PlanResponse toResponse(Plan plan);

  UsageResponse toUsageResponse(Usage usage);
}
