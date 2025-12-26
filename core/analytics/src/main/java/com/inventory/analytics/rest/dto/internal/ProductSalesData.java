package com.inventory.analytics.rest.dto.internal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductSalesData {
  private String inventoryId;
  private String productName;
  private String lotId;
  private String companyName;
  private int quantitySold = 0;
  private BigDecimal totalRevenue = BigDecimal.ZERO;
  private int numberOfSales = 0;

  public ProductSalesData(String inventoryId) {
    this.inventoryId = inventoryId;
  }
}

