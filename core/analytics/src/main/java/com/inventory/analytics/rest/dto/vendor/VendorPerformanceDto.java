package com.inventory.analytics.rest.dto.vendor;

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
  private BigDecimal averageDaysInStock; // Average days items stay in stock
  private BigDecimal fastMovingItemsPercentage; // % of items that sell quickly
  private BigDecimal deadStockValue; // Value of unsold stock
  private BigDecimal expiredStockValue; // Value of expired stock
  private BigDecimal expiryLossPercentage; // % of stock that expired
  private Integer totalExpiredItems;
  private Integer totalDeadStockItems;
  private BigDecimal riskScore; // Calculated risk score (0-100)
  private String riskLevel; // LOW, MEDIUM, HIGH
}

