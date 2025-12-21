package com.inventory.analytics.rest.dto.profit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductProfitDto {
  private String inventoryId;
  private String productName;
  private String lotId;
  private String companyName;
  private String businessType;
  private Integer totalQuantitySold;
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal grossProfit;
  private BigDecimal marginPercent;
  private Integer numberOfSales;
}

