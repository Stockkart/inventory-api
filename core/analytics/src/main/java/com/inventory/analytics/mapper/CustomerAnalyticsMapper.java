package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.CustomerAnalyticsResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface CustomerAnalyticsMapper {

  @Mapping(target = "meta", ignore = true)
  CustomerAnalyticsResponse toResponse(CustomerAnalyticsResponseParams params);

  @AfterMapping
  default void setMeta(@MappingTarget CustomerAnalyticsResponse response, CustomerAnalyticsResponseParams params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("startDate", params.getStartDate());
    meta.put("endDate", params.getEndDate());
    meta.put("totalPurchases", params.getTotalPurchases());
    meta.put("totalAllPurchases", params.getTotalAllPurchases());
    meta.put("topN", params.getTopN());
    meta.put("includeAll", params.isIncludeAll());
    meta.put("totalCustomers", params.getTotalCustomers());
    response.setMeta(meta);
  }
}
