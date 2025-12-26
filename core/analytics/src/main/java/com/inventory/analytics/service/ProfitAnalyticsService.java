package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.helper.ProfitAnalyticsHelper;
import com.inventory.analytics.helper.SalesAnalyticsHelper;
import com.inventory.analytics.rest.dto.profit.ProfitAnalyticsResponse;
import com.inventory.analytics.rest.dto.profit.ProductProfitDto;
import com.inventory.product.domain.model.Purchase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@Transactional(readOnly = true)
public class ProfitAnalyticsService {

  @Autowired
  private ProfitAnalyticsHelper profitHelper;

  @Autowired
  private SalesAnalyticsHelper salesHelper;

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

      // Get all completed purchases in date range
      List<Purchase> purchases = salesHelper.getCompletedPurchases(shopId, startDate, endDate);

      // Build response
      ProfitAnalyticsResponse response = new ProfitAnalyticsResponse();

      // Calculate product-level profits
      List<ProductProfitDto> productProfits = profitHelper.calculateProductProfits(purchases);
      response.setProductProfits(productProfits);

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

      response.setTotalRevenue(totalRevenue);
      response.setTotalCost(totalCost);
      response.setTotalGrossProfit(totalGrossProfit);
      response.setOverallMarginPercent(overallMarginPercent);
      response.setTotalItemsSold(totalItemsSold);
      response.setTotalPurchases(purchases.size());

      // Sales by group
      if (StringUtils.hasText(groupBy)) {
        switch (groupBy.toLowerCase()) {
          case "product":
            response.setProfitByProduct(profitHelper.calculateProfitByProduct(purchases));
            break;
          case "lotid":
          case "lot":
            response.setProfitByLotId(profitHelper.calculateProfitByLotId(purchases));
            break;
          case "businesstype":
          case "business":
            response.setProfitByBusinessType(profitHelper.calculateProfitByBusinessType(purchases));
            break;
        }
      } else {
        // Return all groupings
        response.setProfitByProduct(profitHelper.calculateProfitByProduct(purchases));
        response.setProfitByLotId(profitHelper.calculateProfitByLotId(purchases));
        response.setProfitByBusinessType(profitHelper.calculateProfitByBusinessType(purchases));
      }

      // Discount impact
      response.setDiscountImpact(profitHelper.calculateDiscountImpact(purchases));

      // Cost vs selling price trends
      if (StringUtils.hasText(timeSeriesGranularity)) {
        response.setCostPriceTrends(profitHelper.calculateCostPriceTrends(purchases, startDate, endDate, timeSeriesGranularity));
      }

      // Low margin products
      response.setLowMarginProducts(profitHelper.getLowMarginProducts(productProfits, lowMarginThreshold));

      // Metadata
      Map<String, Object> meta = new HashMap<>();
      meta.put("startDate", startDate);
      meta.put("endDate", endDate);
      meta.put("totalPurchases", purchases.size());
      meta.put("lowMarginThreshold", lowMarginThreshold);
      response.setMeta(meta);

      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in profit analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting profit analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving profit analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting profit analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve profit analytics");
    }
  }
}

