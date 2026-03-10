package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.VendorCategoryExpiryDto;
import com.inventory.analytics.rest.dto.response.VendorDependencyDto;
import com.inventory.analytics.rest.dto.response.VendorPerformanceDto;
import com.inventory.analytics.rest.dto.response.VendorRevenueDto;
import com.inventory.analytics.rest.dto.response.VendorStockDto;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class VendorAnalyticsResponseParams {

  private List<VendorStockDto> vendorStockAnalytics;
  private List<VendorRevenueDto> vendorRevenueAnalytics;
  private List<VendorPerformanceDto> vendorPerformanceAnalytics;
  private List<VendorCategoryExpiryDto> categoryExpiryAnalytics;
  private List<VendorDependencyDto> vendorDependencyAnalytics;
  private int totalVendors;
  private BigDecimal totalInventoryValue;
  private BigDecimal totalRevenue;
  private BigDecimal totalExpiredStockValue;
  private BigDecimal totalUnsoldStockValue;
  private BigDecimal topVendorRevenuePercentage;
  private BigDecimal top3VendorRevenuePercentage;
  private String mostDependentVendorId;
  private String mostDependentVendorName;
  private Instant startDate;
  private Instant endDate;
  private int totalInventories;
  private int totalPurchases;
}
