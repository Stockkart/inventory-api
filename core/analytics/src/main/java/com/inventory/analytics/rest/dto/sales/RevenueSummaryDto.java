package com.inventory.analytics.rest.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RevenueSummaryDto {
  private BigDecimal totalRevenue;
  private Integer totalPurchases;
  private BigDecimal averageOrderValue;
  private BigDecimal totalTax;
  private BigDecimal totalDiscount;
  private BigDecimal additionalTotalDiscount;
}

