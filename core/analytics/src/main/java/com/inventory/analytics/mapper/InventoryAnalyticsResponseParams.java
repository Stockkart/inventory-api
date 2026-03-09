package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.InventoryAnalyticsDto;
import com.inventory.analytics.rest.dto.response.InventorySummaryDto;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class InventoryAnalyticsResponseParams {

  private InventorySummaryDto summary;
  private List<InventoryAnalyticsDto> lowStockItems;
  private List<InventoryAnalyticsDto> notSellingItems;
  private List<InventoryAnalyticsDto> expiringSoonItems;
  private List<InventoryAnalyticsDto> expiredItems;
  private List<InventoryAnalyticsDto> deadStockItems;
  private List<InventoryAnalyticsDto> allItems;
  private Integer lowStockThreshold;
  private Integer deadStockDays;
  private Integer expiringSoonDays;
  private Boolean includeAll;
  private int totalItems;
}
