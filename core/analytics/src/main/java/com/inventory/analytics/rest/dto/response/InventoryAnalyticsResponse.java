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
  /**
   * Rows each paged list has in full ({@code lowStockItems}, {@code notSellingItems}, {@code
   * deadStockItems}, and {@code allItems} when asked for); the lists above carry the first page
   * ({@code meta.listLimit} rows). Further pages: {@code GET /analytics/inventory/items}.
   */
  private Map<String, Integer> listTotals;
  private Map<String, Object> meta;
}
