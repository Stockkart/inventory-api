package com.inventory.analytics.rest.dto.vendor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorDependencyDto {
  private String vendorId;
  private String vendorName;
  private String vendorCompanyName;
  private BigDecimal revenuePercentage; // % of total revenue from this vendor
  private BigDecimal inventoryPercentage; // % of total inventory from this vendor
  private Integer numberOfProducts;
  private BigDecimal dependencyScore; // 0-100, higher = more dependent
  private String dependencyLevel; // LOW, MEDIUM, HIGH, CRITICAL
}

