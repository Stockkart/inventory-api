package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.SalesAnalyticsResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface SalesAnalyticsMapper {

  @Mapping(target = "meta", ignore = true)
  SalesAnalyticsResponse toResponse(SalesAnalyticsResponseParams params);

  @AfterMapping
  default void setMeta(@MappingTarget SalesAnalyticsResponse response, SalesAnalyticsResponseParams params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("startDate", params.getStartDate());
    meta.put("endDate", params.getEndDate());
    meta.put("totalPurchases", params.getTotalPurchases());
    response.setMeta(meta);
  }
}
