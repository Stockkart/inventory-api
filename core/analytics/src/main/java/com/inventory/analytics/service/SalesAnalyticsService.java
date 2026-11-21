package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.SalesAnalyticsMapper;
import com.inventory.analytics.mapper.SalesAnalyticsResponseParams;
import com.inventory.analytics.rest.dto.response.*;
import com.inventory.analytics.utils.AnalyticsSalesQueries;
import com.inventory.analytics.utils.AnalyticsSalesQueries.BillTotals;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.analytics.utils.SalesAnalyticsUtils;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.product.domain.model.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class SalesAnalyticsService {

  @Autowired
  private SalesAnalyticsUtils salesUtils;

  @Autowired
  private AnalyticsSalesQueries salesQueries;

  @Autowired
  private SalesAnalyticsMapper salesAnalyticsMapper;

  @Autowired
  private MetricsWrapper metrics;

  /**
   * Get sales analytics for a date range.
   *
   * @param shopId the shop ID
   * @param startDate start date (optional, defaults to 30 days ago)
   * @param endDate end date (optional, defaults to now)
   * @param groupBy grouping type: product, lotId, company (optional)
   * @param timeSeriesGranularity hour, day, week, month (optional)
   * @param topN number of top products to return (optional, defaults to 10)
   * @param compareWithPrevious whether to compare with previous period (optional, defaults to false)
   * @return sales analytics response
   */
  public SalesAnalyticsResponse getSalesAnalytics(
      String shopId,
      Instant startDate,
      Instant endDate,
      String groupBy,
      String timeSeriesGranularity,
      Integer topN,
      Boolean compareWithPrevious) {

    // Validate shopId
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "User not authenticated or shop not found");
    }

    metrics.record(
        AnalyticsMetricsConstants.QUERIES_TOTAL,
        1,
        "module",
        AnalyticsMetricsConstants.MODULE,
        "operation",
        "sales");

    try {
      // Set default values
      if (endDate == null) {
        endDate = Instant.now();
      }
      if (startDate == null) {
        startDate = endDate.minus(30, ChronoUnit.DAYS);
      }
      if (topN == null || topN <= 0) {
        topN = 10;
      }
      if (compareWithPrevious == null) {
        compareWithPrevious = false;
      }

      log.debug("Getting sales analytics for shop: {} from {} to {}", shopId, startDate, endDate);

      // MongoDB adds up bills and groups identical lines; the lots are read once
      BillTotals totals = salesQueries.billTotals(shopId, startDate, endDate);
      List<LineGroup> lines = salesQueries.lineGroups(shopId, startDate, endDate, null, false);
      Map<String, Inventory> lots = salesQueries.lotsOf(lines);

      // Build response data
      RevenueSummaryDto summary = salesUtils.calculateRevenueSummary(totals);
      List<TopProductDto> topProducts = salesUtils.getTopProducts(lines, lots, topN);
      List<SalesByGroupDto> salesByProduct;
      List<SalesByGroupDto> salesByLotId;
      List<SalesByGroupDto> salesByCompany;
      if (StringUtils.hasText(groupBy)) {
        switch (groupBy.toLowerCase()) {
          case "product":
            salesByProduct = salesUtils.getSalesByProduct(lines);
            salesByLotId = null;
            salesByCompany = null;
            break;
          case "lotid":
          case "lot":
            salesByProduct = null;
            salesByLotId = salesUtils.getSalesByLotId(lines, lots);
            salesByCompany = null;
            break;
          case "company":
          case "companyname":
            salesByProduct = null;
            salesByLotId = null;
            salesByCompany = salesUtils.getSalesByCompany(lines, lots);
            break;
          default:
            salesByProduct = salesUtils.getSalesByProduct(lines);
            salesByLotId = salesUtils.getSalesByLotId(lines, lots);
            salesByCompany = salesUtils.getSalesByCompany(lines, lots);
        }
      } else {
        salesByProduct = salesUtils.getSalesByProduct(lines);
        salesByLotId = salesUtils.getSalesByLotId(lines, lots);
        salesByCompany = salesUtils.getSalesByCompany(lines, lots);
      }
      List<TimeSeriesDataDto> timeSeries = StringUtils.hasText(timeSeriesGranularity)
          ? salesUtils.getTimeSeriesData(
              salesQueries.bucketTotals(shopId, startDate, endDate, timeSeriesGranularity), timeSeriesGranularity)
          : null;
      PeriodComparisonDto periodComparison = null;
      if (compareWithPrevious) {
        long periodDays = ChronoUnit.DAYS.between(startDate, endDate);
        Instant previousStartDate = startDate.minus(periodDays, ChronoUnit.DAYS);
        Instant previousEndDate = startDate;
        periodComparison = salesUtils.calculatePeriodComparison(
            totals, salesQueries.billTotals(shopId, previousStartDate, previousEndDate));
      }

      SalesAnalyticsResponseParams params = SalesAnalyticsResponseParams.builder()
          .summary(summary)
          .topProducts(topProducts)
          .salesByProduct(salesByProduct)
          .salesByLotId(salesByLotId)
          .salesByCompany(salesByCompany)
          .timeSeries(timeSeries)
          .periodComparison(periodComparison)
          .startDate(startDate)
          .endDate(endDate)
          .totalPurchases(Math.toIntExact(totals.bills()))
          .build();

      return salesAnalyticsMapper.toResponse(params);

    } catch (ValidationException e) {
      log.warn("Validation error in sales analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting sales analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving sales analytics", e);
    } catch (Exception e) {
      log.error("Unexpected error while getting sales analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve sales analytics", e);
    }
  }
}
