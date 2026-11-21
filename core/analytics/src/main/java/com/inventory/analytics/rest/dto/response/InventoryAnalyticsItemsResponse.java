package com.inventory.analytics.rest.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One page of one Inventory-tab list ({@code GET /analytics/inventory/items}). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAnalyticsItemsResponse {
  /** {@code lowStockItems}, {@code notSellingItems}, {@code deadStockItems} or {@code allItems}. */
  private String list;
  private List<InventoryAnalyticsDto> items;
  /** 0-based. */
  private int page;
  private int size;
  /** Rows the list has in full. */
  private int total;
}
