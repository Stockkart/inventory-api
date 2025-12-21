package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.rest.dto.sales.*;
import com.inventory.analytics.helper.SalesAnalyticsHelper;
import com.inventory.product.domain.model.Purchase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class SalesAnalyticsService {

  @Autowired
  private SalesAnalyticsHelper analyticsHelper;

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
      List<Purchase> purchases = analyticsHelper.getCompletedPurchases(shopId, startDate, endDate);

      // Build response
      SalesAnalyticsResponse response = new SalesAnalyticsResponse();

      // Revenue summary
      response.setSummary(analyticsHelper.calculateRevenueSummary(purchases));

      // Top products
      response.setTopProducts(analyticsHelper.getTopProducts(purchases, topN));

      // Sales by group
      if (StringUtils.hasText(groupBy)) {
        switch (groupBy.toLowerCase()) {
          case "product":
            response.setSalesByProduct(analyticsHelper.getSalesByProduct(purchases));
            break;
          case "lotid":
          case "lot":
            response.setSalesByLotId(analyticsHelper.getSalesByLotId(purchases));
            break;
          case "company":
          case "companyname":
            response.setSalesByCompany(analyticsHelper.getSalesByCompany(purchases));
            break;
        }
      } else {
        // Return all groupings
        response.setSalesByProduct(analyticsHelper.getSalesByProduct(purchases));
        response.setSalesByLotId(analyticsHelper.getSalesByLotId(purchases));
        response.setSalesByCompany(analyticsHelper.getSalesByCompany(purchases));
      }

      // Time series
      if (StringUtils.hasText(timeSeriesGranularity)) {
        response.setTimeSeries(analyticsHelper.getTimeSeriesData(purchases, startDate, endDate, timeSeriesGranularity));
      }

      // Period comparison
      if (compareWithPrevious) {
        long periodDays = ChronoUnit.DAYS.between(startDate, endDate);
        Instant previousStartDate = startDate.minus(periodDays, ChronoUnit.DAYS);
        Instant previousEndDate = startDate;
        List<Purchase> previousPurchases = analyticsHelper.getCompletedPurchases(shopId, previousStartDate, previousEndDate);
        response.setPeriodComparison(analyticsHelper.calculatePeriodComparison(purchases, previousPurchases));
      }

      // Metadata
      Map<String, Object> meta = new HashMap<>();
      meta.put("startDate", startDate);
      meta.put("endDate", endDate);
      meta.put("totalPurchases", purchases.size());
      response.setMeta(meta);

      return response;

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
