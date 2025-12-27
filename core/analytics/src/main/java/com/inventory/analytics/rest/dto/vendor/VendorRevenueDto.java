package com.inventory.analytics.rest.dto.vendor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorRevenueDto {
  private String vendorId;
  private String vendorName;
  private String vendorCompanyName;
  private BigDecimal totalRevenue;
  private BigDecimal totalCost;
  private BigDecimal grossProfit;
  private BigDecimal marginPercent;
  private Integer totalItemsSold;
  private Integer totalPurchases;
}

