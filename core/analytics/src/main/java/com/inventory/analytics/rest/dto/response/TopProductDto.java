package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TopProductDto {
  private String inventoryId;
  private String productName;
  private String lotId;
  private String companyName;
  private Integer totalQuantitySold;
  private BigDecimal totalRevenue;
  private Integer numberOfSales;
}
