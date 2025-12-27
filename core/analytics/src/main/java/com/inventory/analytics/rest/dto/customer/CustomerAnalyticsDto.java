package com.inventory.analytics.rest.dto.customer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerAnalyticsDto {
  private String customerId;
  private String customerName;
  private String customerPhone;
  private String customerEmail;
  private Integer totalPurchases;
  private BigDecimal totalRevenue;
  private BigDecimal averageOrderValue;
  private BigDecimal customerLifetimeValue;
  private BigDecimal purchaseFrequency; // Purchases per month
  private Instant firstPurchaseDate;
  private Instant lastPurchaseDate;
  private Long daysSinceLastPurchase;
  private Boolean isRepeatCustomer;
  private Integer purchaseCountInPeriod;
}

