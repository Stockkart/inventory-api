package com.inventory.analytics.rest.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupSalesData {
  private String groupKey;
  private int quantitySold = 0;
  private BigDecimal totalRevenue = BigDecimal.ZERO;
  private int numberOfSales = 0;

  public GroupSalesData(String groupKey) {
    this.groupKey = groupKey;
  }
}

