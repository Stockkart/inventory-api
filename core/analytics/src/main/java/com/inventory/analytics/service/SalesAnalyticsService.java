package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.SalesAnalyticsMapper;
import com.inventory.analytics.mapper.SalesAnalyticsResponseParams;
import com.inventory.analytics.rest.dto.response.*;
import com.inventory.analytics.utils.SalesAnalyticsUtils;
import com.inventory.product.domain.model.Purchase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class SalesAnalyticsService {

  @Autowired
  private SalesAnalyticsUtils salesUtils;

  @Autowired
  private SalesAnalyticsMapper salesAnalyticsMapper;

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

      // Get all completed purchases in date range
      List<Purchase> purchases = salesUtils.getCompletedPurchases(shopId, startDate, endDate);

      // Build response data
      RevenueSummaryDto summary = salesUtils.calculateRevenueSummary(purchases);
      List<TopProductDto> topProducts = salesUtils.getTopProducts(purchases, topN);
      List<SalesByGroupDto> salesByProduct;
      List<SalesByGroupDto> salesByLotId;
      List<SalesByGroupDto> salesByCompany;
      if (StringUtils.hasText(groupBy)) {
        switch (groupBy.toLowerCase()) {
          case "product":
            salesByProduct = salesUtils.getSalesByProduct(purchases);
            salesByLotId = null;
            salesByCompany = null;
            break;
          case "lotid":
          case "lot":
            salesByProduct = null;
            salesByLotId = salesUtils.getSalesByLotId(purchases);
            salesByCompany = null;
            break;
          case "company":
          case "companyname":
            salesByProduct = null;
            salesByLotId = null;
            salesByCompany = salesUtils.getSalesByCompany(purchases);
            break;
          default:
            salesByProduct = salesUtils.getSalesByProduct(purchases);
            salesByLotId = salesUtils.getSalesByLotId(purchases);
            salesByCompany = salesUtils.getSalesByCompany(purchases);
        }
      } else {
        salesByProduct = salesUtils.getSalesByProduct(purchases);
        salesByLotId = salesUtils.getSalesByLotId(purchases);
        salesByCompany = salesUtils.getSalesByCompany(purchases);
      }
      List<TimeSeriesDataDto> timeSeries = StringUtils.hasText(timeSeriesGranularity)
          ? salesUtils.getTimeSeriesData(purchases, startDate, endDate, timeSeriesGranularity)
          : null;
      PeriodComparisonDto periodComparison = null;
      if (compareWithPrevious) {
        long periodDays = ChronoUnit.DAYS.between(startDate, endDate);
        Instant previousStartDate = startDate.minus(periodDays, ChronoUnit.DAYS);
        Instant previousEndDate = startDate;
        List<Purchase> previousPurchases = salesUtils.getCompletedPurchases(shopId, previousStartDate, previousEndDate);
        periodComparison = salesUtils.calculatePeriodComparison(purchases, previousPurchases);
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
          .totalPurchases(purchases.size())
          .build();

      return salesAnalyticsMapper.toResponse(params);

    } catch (ValidationException e) {
      log.warn("Validation error in sales analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting sales analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving sales analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting sales analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve sales analytics");
    }
  }
}
