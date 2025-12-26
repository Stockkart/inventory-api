package com.inventory.analytics.rest.dto.vendor;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VendorAnalyticsResponse {
  // Overall summary
  private Integer totalVendors;
  private BigDecimal totalInventoryValue;
  private BigDecimal totalRevenue;
  private BigDecimal totalExpiredStockValue;
  private BigDecimal totalUnsoldStockValue;

  // Stock analytics
  private List<VendorStockDto> vendorStockAnalytics;

  // Revenue analytics
  private List<VendorRevenueDto> vendorRevenueAnalytics;

  // Performance analytics
  private List<VendorPerformanceDto> vendorPerformanceAnalytics;

  // Category expiry analytics
  private List<VendorCategoryExpiryDto> categoryExpiryAnalytics;

  // Dependency analytics
  private List<VendorDependencyDto> vendorDependencyAnalytics;
  private BigDecimal topVendorRevenuePercentage; // % of revenue from top vendor
  private BigDecimal top3VendorRevenuePercentage; // % of revenue from top 3 vendors
  private String mostDependentVendorId;
  private String mostDependentVendorName;

  // Metadata
  private Map<String, Object> meta;
}

