package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CostPriceTrendDto {
  private String period;
  private Instant startTime;
  private Instant endTime;
  private BigDecimal averageCostPrice;
  private BigDecimal averagePriceToRetail;
  private BigDecimal averageMargin;
  private BigDecimal averageMarginPercent;
  private Integer totalItemsSold;
}
