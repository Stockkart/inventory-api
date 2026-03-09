package com.inventory.analytics.mapper;

import com.inventory.analytics.rest.dto.response.PeriodComparisonDto;
import com.inventory.analytics.rest.dto.response.RevenueSummaryDto;
import com.inventory.analytics.rest.dto.response.SalesByGroupDto;
import com.inventory.analytics.rest.dto.response.TimeSeriesDataDto;
import com.inventory.analytics.rest.dto.response.TopProductDto;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class SalesAnalyticsResponseParams {

  private RevenueSummaryDto summary;
  private List<TopProductDto> topProducts;
  private List<SalesByGroupDto> salesByProduct;
  private List<SalesByGroupDto> salesByLotId;
  private List<SalesByGroupDto> salesByCompany;
  private List<TimeSeriesDataDto> timeSeries;
  private PeriodComparisonDto periodComparison;
  private Instant startDate;
  private Instant endDate;
  private int totalPurchases;
}
