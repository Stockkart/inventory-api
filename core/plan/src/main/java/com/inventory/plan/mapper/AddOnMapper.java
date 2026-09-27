package com.inventory.plan.mapper;

import com.inventory.plan.domain.model.AddOn;
import com.inventory.plan.domain.model.ShopAddOn;
import com.inventory.plan.rest.dto.request.AddOnAdminRequest;
import com.inventory.plan.rest.dto.response.AddOnResponse;
import com.inventory.plan.rest.dto.response.AdminAddOnResponse;
import com.inventory.plan.rest.dto.response.ShopAddOnResponse;
import java.time.Instant;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface AddOnMapper {

  AddOnResponse toResponse(AddOn addOn);

  @Mapping(target = "active", expression = "java(addOn.isActive())")
  AdminAddOnResponse toAdminResponse(AddOn addOn);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "active", constant = "true")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  AddOn toEntity(AddOnAdminRequest request);

  @Mapping(target = "live", expression = "java(shopAddOn.isLive(now))")
  ShopAddOnResponse toShopAddOnResponse(ShopAddOn shopAddOn, Instant now);
}
