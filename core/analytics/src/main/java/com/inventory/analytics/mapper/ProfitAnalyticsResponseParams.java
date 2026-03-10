package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.CostPriceTrendDto;
import com.inventory.analytics.rest.dto.response.DiscountImpactDto;
import com.inventory.analytics.rest.dto.response.ProductProfitDto;
import com.inventory.analytics.rest.dto.response.ProfitByGroupDto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class ProfitAnalyticsResponseParams {

  private List<ProductProfitDto> productProfits;
  private List<ProfitByGroupDto> profitByProduct;
  private List<ProfitByGroupDto> profitByLotId;
  private List<ProfitByGroupDto> profitByBusinessType;
  private DiscountImpactDto discountImpact;
  private List<CostPriceTrendDto> costPriceTrends;
  private List<ProductProfitDto> lowMarginProducts;
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal totalGrossProfit;
  private BigDecimal overallMarginPercent;
  private int totalItemsSold;
  private int totalPurchases;
  private Instant startDate;
  private Instant endDate;
  private BigDecimal lowMarginThreshold;
}
