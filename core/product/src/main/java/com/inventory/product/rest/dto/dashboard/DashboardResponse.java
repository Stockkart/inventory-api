package com.inventory.product.rest.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResponse {
  private KeyMetrics keyMetrics;
  private RevenueBreakdown revenueBreakdown;
  private ProductInsights productInsights;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class KeyMetrics {
    private Long totalProducts;
    private BigDecimal totalRevenueToday;
    private Long ordersToday;
    private Long lowStockItemsCount;
    private BigDecimal averageOrderValue;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RevenueBreakdown {
    private BigDecimal today;
    private BigDecimal yesterday;
    private BigDecimal thisWeek;
    private BigDecimal thisMonth;
    private BigDecimal percentageChangeToday; // Percentage change from yesterday
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ProductInsights {
    private Long totalUniqueProducts;
    private Long productsAddedToday;
    private Long productsAddedThisWeek;
    private Long productsAddedThisMonth;
    private Long outOfStockItems;
  }
}

