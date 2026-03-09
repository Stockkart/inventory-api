package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.VendorAnalyticsResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface VendorAnalyticsMapper {

  @Mapping(target = "meta", ignore = true)
  VendorAnalyticsResponse toResponse(VendorAnalyticsResponseParams params);

  @AfterMapping
  default void setMeta(@MappingTarget VendorAnalyticsResponse response, VendorAnalyticsResponseParams params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("startDate", params.getStartDate());
    meta.put("endDate", params.getEndDate());
    meta.put("totalInventories", params.getTotalInventories());
    meta.put("totalPurchases", params.getTotalPurchases());
    response.setMeta(meta);
  }
}
