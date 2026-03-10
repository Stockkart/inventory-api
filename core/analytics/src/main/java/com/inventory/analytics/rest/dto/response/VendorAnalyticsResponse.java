package com.inventory.analytics.rest.dto.response;

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
  private Integer totalVendors;
  private BigDecimal totalInventoryValue;
  private BigDecimal totalRevenue;
  private BigDecimal totalExpiredStockValue;
  private BigDecimal totalUnsoldStockValue;

  private List<VendorStockDto> vendorStockAnalytics;
  private List<VendorRevenueDto> vendorRevenueAnalytics;
  private List<VendorPerformanceDto> vendorPerformanceAnalytics;
  private List<VendorCategoryExpiryDto> categoryExpiryAnalytics;
  private List<VendorDependencyDto> vendorDependencyAnalytics;
  private BigDecimal topVendorRevenuePercentage;
  private BigDecimal top3VendorRevenuePercentage;
  private String mostDependentVendorId;
  private String mostDependentVendorName;

  private Map<String, Object> meta;
}
