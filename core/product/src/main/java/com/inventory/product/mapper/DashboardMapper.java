package com.inventory.product.mapper;

import com.inventory.product.rest.dto.response.DashboardResponse;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface DashboardMapper {

  /**
   * Build dashboard response from computed metrics and insights.
   */
  default DashboardResponse toDashboardResponse(
      DashboardResponse.KeyMetrics keyMetrics,
      List<DashboardResponse.LowStockItem> lowStockItems,
      DashboardResponse.RevenueBreakdown revenueBreakdown,
      DashboardResponse.ProductInsights productInsights,
      DashboardResponse.SalesTrend salesTrend) {
    DashboardResponse response = new DashboardResponse();
    response.setKeyMetrics(keyMetrics);
    response.setLowStockItems(lowStockItems);
    response.setRevenueBreakdown(revenueBreakdown);
    response.setProductInsights(productInsights);
    response.setSalesTrend(salesTrend);
    return response;
  }
}
