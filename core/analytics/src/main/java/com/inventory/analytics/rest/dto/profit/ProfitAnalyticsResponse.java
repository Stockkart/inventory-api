package com.inventory.analytics.rest.dto.profit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfitAnalyticsResponse {
  // Overall summary
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal totalGrossProfit;
  private BigDecimal overallMarginPercent;
  private Integer totalItemsSold;
  private Integer totalPurchases;

  // Product-level profit
  private List<ProductProfitDto> productProfits;

  // Grouped by product name
  private List<ProfitByGroupDto> profitByProduct;

  // Grouped by lotId
  private List<ProfitByGroupDto> profitByLotId;

  // Grouped by businessType
  private List<ProfitByGroupDto> profitByBusinessType;

  // Discount impact
  private DiscountImpactDto discountImpact;

  // Cost vs selling price trends (time-series)
  private List<CostPriceTrendDto> costPriceTrends;

  // Low margin products (margin < threshold, default 10%)
  private List<ProductProfitDto> lowMarginProducts;

  // Metadata
  private Map<String, Object> meta;
}

