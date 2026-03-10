package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.InventoryAnalyticsResponse;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.HashMap;
import java.util.Map;

@Mapper(componentModel = "spring")
public interface InventoryAnalyticsMapper {

  @Mapping(target = "meta", ignore = true)
  InventoryAnalyticsResponse toResponse(InventoryAnalyticsResponseParams params);

  @AfterMapping
  default void setMeta(@MappingTarget InventoryAnalyticsResponse response, InventoryAnalyticsResponseParams params) {
    Map<String, Object> meta = new HashMap<>();
    meta.put("lowStockThreshold", params.getLowStockThreshold());
    meta.put("deadStockDays", params.getDeadStockDays());
    meta.put("expiringSoonDays", params.getExpiringSoonDays());
    meta.put("includeAll", params.getIncludeAll());
    meta.put("totalItems", params.getTotalItems());
    response.setMeta(meta);
  }
}
