package com.inventory.analytics.rest.dto.inventory;

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
  private List<InventoryAnalyticsDto> notSellingItems; // Dead stock (no movement for deadStockDays), same as deadStockItems
  private List<InventoryAnalyticsDto> expiringSoonItems;
  private List<InventoryAnalyticsDto> expiredItems;
  private List<InventoryAnalyticsDto> deadStockItems;
  private List<InventoryAnalyticsDto> allItems; // All inventory items (optional)
  private Map<String, Object> meta;
}

