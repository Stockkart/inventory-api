package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisStockSummaryDto {
  private long lotCount;
  private BigDecimal onHandQty;
  private BigDecimal costValuation;
  private BigDecimal sellValuation;
  private BigDecimal potentialProfit;
  private long lowStockCount;
  private long deadStockCount;
}
