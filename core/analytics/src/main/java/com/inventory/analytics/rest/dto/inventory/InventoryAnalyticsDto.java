package com.inventory.analytics.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAnalyticsDto {
  private String inventoryId;
  private String lotId;
  private String barcode;
  private String productName;
  private String companyName;
  private String businessType;
  private String location;
  
  // Stock levels
  private Integer receivedCount;
  private Integer soldCount;
  private Integer currentCount;
  private Boolean isLowStock;
  private BigDecimal stockPercentage; // currentCount / receivedCount * 100
  
  // Aging
  private Long daysSinceReceived;
  private Long daysUntilExpiry;
  private Boolean isExpiringSoon;
  private Boolean isExpired;
  
  // Turnover
  private BigDecimal turnoverRatio; // soldCount / average inventory
  private Boolean isDeadStock; // No sales in X days
  
  // Value
  private BigDecimal costValue; // currentCount * costPrice
  private BigDecimal retailValue; // currentCount * priceToRetail
  private BigDecimal potentialProfit; // retailValue - costValue
  private BigDecimal marginPercent;
  
  // Dates
  private Instant receivedDate;
  private Instant expiryDate;
  private Instant lastSoldDate; // Last time this inventory was sold
}

