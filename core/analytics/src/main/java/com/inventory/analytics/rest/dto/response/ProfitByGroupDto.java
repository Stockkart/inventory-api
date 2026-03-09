package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfitByGroupDto {
  private String groupKey;
  private Integer totalQuantitySold;
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal grossProfit;
  private BigDecimal marginPercent;
  private Integer numberOfSales;
}
