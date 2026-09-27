package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.SaleCampaign;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import com.inventory.plan.rest.dto.response.AdminCampaignResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface CampaignMapper {

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  SaleCampaign toEntity(CampaignRequest request);

  @Mapping(target = "active", expression = "java(!Boolean.FALSE.equals(campaign.getActive()))")
  @Mapping(target = "state", ignore = true)
  AdminCampaignResponse toAdminResponse(SaleCampaign campaign);
}
