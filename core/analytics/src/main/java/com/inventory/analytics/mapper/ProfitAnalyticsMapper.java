package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.ProfitAnalyticsResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface ProfitAnalyticsMapper {

  @Mapping(target = "meta", ignore = true)
  ProfitAnalyticsResponse toResponse(ProfitAnalyticsResponseParams params);

  @AfterMapping
  default void setMeta(@MappingTarget ProfitAnalyticsResponse response, ProfitAnalyticsResponseParams params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("startDate", params.getStartDate());
    meta.put("endDate", params.getEndDate());
    meta.put("totalPurchases", params.getTotalPurchases());
    meta.put("lowMarginThreshold", params.getLowMarginThreshold());
    response.setMeta(meta);
  }
}
