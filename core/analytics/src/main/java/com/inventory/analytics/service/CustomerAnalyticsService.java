package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.helper.CustomerAnalyticsHelper;
import com.inventory.analytics.rest.dto.customer.CustomerAnalyticsDto;
import com.inventory.analytics.rest.dto.customer.CustomerAnalyticsResponse;
import com.inventory.analytics.rest.dto.customer.CustomerSummaryDto;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class CustomerAnalyticsService {

  @Autowired
  private CustomerAnalyticsHelper analyticsHelper;

  /**
   * Get comprehensive customer analytics.
   *
   * @param shopId the shop ID
   * @param startDate start date (optional, defaults to 30 days ago)
   * @param endDate end date (optional, defaults to now)
   * @param topN number of top customers to return (optional, defaults to 10)
   * @param includeAll whether to include all customers in response (optional, defaults to false)
   * @return customer analytics response
   */
  public CustomerAnalyticsResponse getCustomerAnalytics(
      String shopId,
      Instant startDate,
      Instant endDate,
      Integer topN,
      Boolean includeAll) {

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
      if (includeAll == null) {
        includeAll = false;
      }

      log.debug("Getting customer analytics for shop: {} from {} to {}", shopId, startDate, endDate);

      // Get all completed purchases (for lifetime value calculation)
      List<Purchase> allPurchases = analyticsHelper.getCompletedPurchases(shopId);

      // Get purchases in date range (for period-specific analytics)
      List<Purchase> purchasesInPeriod = analyticsHelper.getCompletedPurchases(shopId, startDate, endDate);

      // Calculate customer analytics (using all purchases for lifetime value, but tracking period purchases)
      List<CustomerAnalyticsDto> allCustomerAnalytics = analyticsHelper.calculateCustomerAnalytics(
          shopId, allPurchases, startDate, endDate);

      // Calculate summary
      CustomerSummaryDto summary = analyticsHelper.calculateCustomerSummary(allCustomerAnalytics);

      // Get top customers
      List<CustomerAnalyticsDto> topCustomers = allCustomerAnalytics.stream()
          .limit(topN)
          .collect(Collectors.toList());

      // Build response
      CustomerAnalyticsResponse response = new CustomerAnalyticsResponse();
      response.setSummary(summary);
      response.setTopCustomers(topCustomers);
      
      // Include all customers if requested
      if (includeAll != null && includeAll) {
        response.setAllCustomers(allCustomerAnalytics);
      } else {
        response.setAllCustomers(null); // Don't include all customers by default to reduce payload size
      }

      // Metadata
      Map<String, Object> meta = new HashMap<>();
      meta.put("startDate", startDate);
      meta.put("endDate", endDate);
      meta.put("totalPurchases", purchasesInPeriod.size());
      meta.put("totalAllPurchases", allPurchases.size());
      meta.put("topN", topN);
      meta.put("includeAll", includeAll);
      meta.put("totalCustomers", allCustomerAnalytics.size());
      response.setMeta(meta);

      return response;

    } catch (ValidationException e) {
      log.warn("Validation error in customer analytics: {}", e.getMessage());
      throw e;
    } catch (DataAccessException e) {
      log.error("Database error while getting customer analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Error retrieving customer analytics");
    } catch (Exception e) {
      log.error("Unexpected error while getting customer analytics: {}", e.getMessage(), e);
      throw new BaseException(ErrorCode.INTERNAL_SERVER_ERROR, "Failed to retrieve customer analytics");
    }
  }
}

