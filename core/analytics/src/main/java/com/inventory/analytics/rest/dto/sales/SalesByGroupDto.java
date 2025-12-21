package com.inventory.analytics.rest.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesByGroupDto {
  private String groupKey; // product name, lotId, or company name
  private Integer totalQuantitySold;
  private BigDecimal totalRevenue;
  private Integer numberOfSales;
}

