package com.inventory.analytics.rest.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PeriodComparisonDto {
  private RevenueSummaryDto currentPeriod;
  private RevenueSummaryDto previousPeriod;
  private BigDecimal revenueChange; // absolute change
  private BigDecimal revenueChangePercent; // percentage change
  private BigDecimal purchaseCountChange;
  private BigDecimal purchaseCountChangePercent;
  private BigDecimal aovChange;
  private BigDecimal aovChangePercent;
}

