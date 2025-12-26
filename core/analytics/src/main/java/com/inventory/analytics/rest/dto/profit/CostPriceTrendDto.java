package com.inventory.analytics.rest.dto.profit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostPriceTrendDto {
  private String period; // e.g., "2024-12-01", "2024-12_week", "2024-12"
  private Instant startTime;
  private Instant endTime;
  private BigDecimal averageCostPrice;
  private BigDecimal averageSellingPrice;
  private BigDecimal averageMargin;
  private BigDecimal averageMarginPercent;
  private Integer totalItemsSold;
}

