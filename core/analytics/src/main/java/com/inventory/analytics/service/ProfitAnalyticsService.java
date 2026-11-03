package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.ProfitAnalyticsMapper;
import com.inventory.analytics.mapper.ProfitAnalyticsResponseParams;
import com.inventory.analytics.utils.AnalyticsSalesQueries;
import com.inventory.analytics.utils.AnalyticsSalesQueries.BucketTotal;
import com.inventory.analytics.utils.AnalyticsSalesQueries.LineGroup;
import com.inventory.analytics.utils.ProfitAnalyticsUtils;
import com.inventory.analytics.utils.constants.AnalyticsMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import com.inventory.analytics.rest.dto.response.ProfitAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.ProductProfitDto;
import com.inventory.analytics.rest.dto.response.ProfitByGroupDto;
import com.inventory.product.domain.model.Inventory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProfitAnalyticsService {

  @Autowired
  private ProfitAnalyticsUtils profitUtils;

  @Autowired
  private AnalyticsSalesQueries salesQueries;

  @Autowired
  private ProfitAnalyticsMapper profitAnalyticsMapper;

  @Autowired
  private MetricsWrapper metrics;

  /**
   * Get profit and margin analytics for a date range.
   *
   * @param shopId the shop ID
   * @param startDate start date (optional, defaults to 30 days ago)
   * @param endDate end date (optional, defaults to now)
   * @param groupBy grouping type: product, lotId, businessType (optional, returns all if not specified)
   * @param timeSeriesGranularity hour, day, week, month (optional)
   * @param lowMarginThreshold margin threshold for low margin products (optional, defaults to 10%)
   * @return profit analytics response
   */
  public ProfitAnalyticsResponse getProfitAnalytics(
      String shopId,
      Instant startDate,
      Instant endDate,
      String groupBy,
      String timeSeriesGranularity,
      BigDecimal lowMarginThreshold) {

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
        "profit");

    try {
      // Set default values
      if (endDate == null) {
        endDate = Instant.now();
      }
      if (startDate == null) {
        startDate = endDate.minus(30, ChronoUnit.DAYS);
      }
      if (lowMarginThreshold == null || lowMarginThreshold.compareTo(BigDecimal.ZERO) <= 0) {
        lowMarginThreshold = BigDecimal.valueOf(10); // Default 10%
      }

      log.debug("Getting profit analytics for shop: {} from {} to {}", shopId, startDate, endDate);

      // MongoDB groups identical lines (per IST bucket when a chart is asked for); lots read once
      boolean withTrends = StringUtils.hasText(timeSeriesGranularity);
      List<LineGroup> lines = salesQueries.lineGroups(
          shopId, startDate, endDate, withTrends ? timeSeriesGranularity : null, false);
      Map<String, Inventory> lots = salesQueries.lotsOf(lines);
      long bills = salesQueries.billTotals(shopId, startDate, endDate).bills();

      // Calculate product-level profits
      List<ProductProfitDto> productProfits = profitUtils.calculateProductProfits(lines, lots);

      // Calculate overall summary
      BigDecimal totalRevenue = productProfits.stream()
          .map(ProductProfitDto::getTotalRevenue)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalCost = productProfits.stream()
          .map(ProductProfitDto::getTotalCost)
          .reduce(BigDecimal.ZERO, BigDecimal::add);

      BigDecimal totalGrossProfit = totalRevenue.subtract(totalCost);
      BigDecimal overallMarginPercent = BigDecimal.ZERO;
      if (totalRevenue.compareTo(BigDecimal.ZERO) > 0) {
        overallMarginPercent = totalGrossProfit
            .divide(totalRevenue, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
      }

      int totalItemsSold = productProfits.stream()
          .mapToInt(ProductProfitDto::getTotalQuantitySold)
          .sum();

      // Sales by group
      List<ProfitByGroupDto> profitByProduct;
      List<ProfitByGroupDto> profitByLotId;
      List<ProfitByGroupDto> profitByBusinessType;
      if (StringUtils.hasText(groupBy)) {
        switch (groupBy.toLowerCase()) {
          case "product":
            profitByProduct = profitUtils.calculateProfitByProduct(lines, lots);
            profitByLotId = null;
            profitByBusinessType = null;
            break;
          case "lotid":
          case "lot":
            profitByProduct = null;
            profitByLotId = profitUtils.calculateProfitByLotId(lines, lots);
            profitByBusinessType = null;
            break;
          case "businesstype":
          case "business":
            profitByProduct = null;
            profitByLotId = null;
            profitByBusinessType = profitUtils.calculateProfitByBusinessType(lines, lots);
            break;
          default:
            profitByProduct = profitUtils.calculateProfitByProduct(lines, lots);
            profitByLotId = profitUtils.calculateProfitByLotId(lines, lots);
            profitByBusinessType = profitUtils.calculateProfitByBusinessType(lines, lots);
        }
      } else {
        profitByProduct = profitUtils.calculateProfitByProduct(lines, lots);
        profitByLotId = profitUtils.calculateProfitByLotId(lines, lots);
        profitByBusinessType = profitUtils.calculateProfitByBusinessType(lines, lots);
      }

      ProfitAnalyticsResponseParams params = ProfitAnalyticsResponseParams.builder()
          .productProfits(productProfits)
          .profitByProduct(profitByProduct)
          .profitByLotId(profitByLotId)
          .profitByBusinessType(profitByBusinessType)
          .discountImpact(profitUtils.calculateDiscountImpact(lines))
          .costPriceTrends(withTrends
              ? profitUtils.calculateCostPriceTrends(
                  lines,
                  salesQueries.bucketTotals(shopId, startDate, endDate, timeSeriesGranularity).stream()
                      .map(BucketTotal::bucket).toList(),
                  lots,
                  timeSeriesGranularity)
              : null)
          .lowMarginProducts(profitUtils.getLowMarginProducts(productProfits, lowMarginThreshold))
          .totalRevenue(totalRevenue)
          .totalCost(totalCost)
          .totalGrossProfit(totalGrossProfit)
          .overallMarginPercent(overallMarginPercent)
          .totalItemsSold(totalItemsSold)
          .totalPurchases(Math.toIntExact(bills))
          .startDate(startDate)
          .endDate(endDate)
          .lowMarginThreshold(lowMarginThreshold)
          .build();

      return profitAnalyticsMapper.toResponse(params);

    } catch (ValidationException e) {
      log.warn("Validation error in profit analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting profit analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving profit analytics", e);
    } catch (Exception e) {
      log.error("Unexpected error while getting profit analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve profit analytics", e);
    }
  }
}

