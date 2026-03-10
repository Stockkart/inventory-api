package com.inventory.product.rest.dto.response;

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
  private List<LowStockItem> lowStockItems;
  private RevenueBreakdown revenueBreakdown;
  private ProductInsights productInsights;
  private SalesTrend salesTrend;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class KeyMetrics {
    private Long totalProducts;
    private BigDecimal totalRevenueToday;
    private Long ordersToday;
    private Long lowStockItemsCount;
    private BigDecimal averageOrderValue;
    private Long totalCustomers;
    private BigDecimal totalRevenueAllTime;
    private Long totalOrdersAllTime;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class LowStockItem {
    private String inventoryId;
    private String name;
    private Integer currentCount;
    private Integer threshold;
    private String lotId;
    private String barcode;
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

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class SalesTrend {
    private List<DailySales> last7Days;
    private BigDecimal bestDayRevenue;
    private String bestDayDate;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class DailySales {
    private String date;
    private BigDecimal revenue;
    private Long orderCount;
  }
}

