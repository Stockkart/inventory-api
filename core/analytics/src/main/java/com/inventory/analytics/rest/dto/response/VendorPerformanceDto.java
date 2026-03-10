package com.inventory.analytics.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorPerformanceDto {
  private String vendorId;
  private String vendorName;
  private String vendorCompanyName;
  private BigDecimal averageDaysInStock;
  private BigDecimal fastMovingItemsPercentage;
  private BigDecimal deadStockValue;
  private BigDecimal expiredStockValue;
  private BigDecimal expiryLossPercentage;
  private Integer totalExpiredItems;
  private Integer totalDeadStockItems;
  private BigDecimal riskScore;
  private String riskLevel;
}
