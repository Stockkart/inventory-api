package com.inventory.analytics.rest.dto.sales;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesAnalyticsResponse {
  private RevenueSummaryDto summary;
  private List<TopProductDto> topProducts;
  private List<SalesByGroupDto> salesByProduct;
  private List<SalesByGroupDto> salesByLotId;
  private List<SalesByGroupDto> salesByCompany;
  private List<TimeSeriesDataDto> timeSeries;
  private PeriodComparisonDto periodComparison;
  private Map<String, Object> meta; // Additional metadata
}

