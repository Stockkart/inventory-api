package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventorySummaryDto {
  private Integer totalProducts;
  private Integer lowStockProducts;
  private Integer expiredProducts;
  private Integer expiringSoonProducts;
  private Integer deadStockProducts;
  private BigDecimal totalCostValue;
  private BigDecimal totalRetailValue;
  private BigDecimal totalPotentialProfit;
  private BigDecimal averageTurnoverRatio;
  private BigDecimal averageStockPercentage;
}
