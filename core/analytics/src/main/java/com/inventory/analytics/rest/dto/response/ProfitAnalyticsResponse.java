package com.inventory.analytics.rest.dto.response;

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
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal totalGrossProfit;
  private BigDecimal overallMarginPercent;
  private Integer totalItemsSold;
  private Integer totalPurchases;

  private List<ProductProfitDto> productProfits;
  private List<ProfitByGroupDto> profitByProduct;
  private List<ProfitByGroupDto> profitByLotId;
  private List<ProfitByGroupDto> profitByBusinessType;
  private DiscountImpactDto discountImpact;
  private List<CostPriceTrendDto> costPriceTrends;
  private List<ProductProfitDto> lowMarginProducts;

  private Map<String, Object> meta;
}
