package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.ReferralAttribution;
import com.inventory.plan.domain.model.ReferralReward;
import com.inventory.plan.rest.dto.response.AdminReferralAttributionResponse;
import com.inventory.plan.rest.dto.response.AdminReferralRewardResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ReferralAdminMapper {

  @Mapping(target = "refereeShopName", source = "refereeShopName")
  @Mapping(target = "referrerShopName", source = "referrerShopName")
  AdminReferralAttributionResponse toAttributionResponse(ReferralAttribution attribution,
      String refereeShopName, String referrerShopName);

  @Mapping(target = "refereeShopName", source = "refereeShopName")
  @Mapping(target = "referrerShopName", source = "referrerShopName")
  AdminReferralRewardResponse toRewardResponse(ReferralReward reward, String refereeShopName, String referrerShopName);
}
