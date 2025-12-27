package com.inventory.analytics.rest.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TimeSeriesData {
  private String periodKey;
  private BigDecimal revenue = BigDecimal.ZERO;
  private int purchaseCount = 0;
  private BigDecimal totalAov = BigDecimal.ZERO;

  public TimeSeriesData(String periodKey) {
    this.periodKey = periodKey;
  }
}

