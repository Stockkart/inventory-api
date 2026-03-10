package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAnalyticsResponse {
  private InventorySummaryDto summary;
  private List<InventoryAnalyticsDto> lowStockItems;
  private List<InventoryAnalyticsDto> notSellingItems;
  private List<InventoryAnalyticsDto> expiringSoonItems;
  private List<InventoryAnalyticsDto> expiredItems;
  private List<InventoryAnalyticsDto> deadStockItems;
  private List<InventoryAnalyticsDto> allItems;
  private Map<String, Object> meta;
}
