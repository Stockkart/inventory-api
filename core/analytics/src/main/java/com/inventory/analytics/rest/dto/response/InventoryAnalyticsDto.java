package com.inventory.analytics.rest.dto.response;

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

  private Integer receivedCount;
  private Integer soldCount;
  private Integer currentCount;
  private Boolean isLowStock;
  private BigDecimal stockPercentage;

  private Long daysSinceReceived;
  private Long daysUntilExpiry;
  private Boolean isExpiringSoon;
  private Boolean isExpired;

  private BigDecimal turnoverRatio;
  private Boolean isDeadStock;

  private BigDecimal costValue;
  private BigDecimal retailValue;
  private BigDecimal potentialProfit;
  private BigDecimal marginPercent;

  private Instant receivedDate;
  private Instant expiryDate;
  private Instant lastSoldDate;
}
