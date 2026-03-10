package com.inventory.analytics.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.analytics.mapper.CustomerAnalyticsMapper;
import com.inventory.analytics.mapper.CustomerAnalyticsResponseParams;
import com.inventory.analytics.utils.CustomerAnalyticsUtils;
import com.inventory.analytics.rest.dto.response.CustomerAnalyticsDto;
import com.inventory.analytics.rest.dto.response.CustomerAnalyticsResponse;
import com.inventory.analytics.rest.dto.response.CustomerSummaryDto;
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
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(readOnly = true)
public class CustomerAnalyticsService {

  @Autowired
  private CustomerAnalyticsUtils customerUtils;

  @Autowired
  private CustomerAnalyticsMapper customerAnalyticsMapper;

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
      List<Purchase> allPurchases = customerUtils.getCompletedPurchases(shopId);

      // Get purchases in date range (for period-specific analytics)
      List<Purchase> purchasesInPeriod = customerUtils.getCompletedPurchases(shopId, startDate, endDate);

      // Calculate customer analytics (using all purchases for lifetime value, but tracking period purchases)
      List<CustomerAnalyticsDto> allCustomerAnalytics = customerUtils.calculateCustomerAnalytics(
          shopId, allPurchases, startDate, endDate);

      // Calculate summary
      CustomerSummaryDto summary = customerUtils.calculateCustomerSummary(allCustomerAnalytics);

      // Get top customers
      List<CustomerAnalyticsDto> topCustomers = allCustomerAnalytics.stream()
          .limit(topN)
          .collect(Collectors.toList());

      // Build response via mapper
      CustomerAnalyticsResponseParams params = CustomerAnalyticsResponseParams.builder()
          .summary(summary)
          .topCustomers(topCustomers)
          .allCustomers(includeAll != null && includeAll ? allCustomerAnalytics : null)
          .startDate(startDate)
          .endDate(endDate)
          .totalPurchases(purchasesInPeriod.size())
          .totalAllPurchases(allPurchases.size())
          .topN(topN)
          .includeAll(Boolean.TRUE.equals(includeAll))
          .totalCustomers(allCustomerAnalytics.size())
          .build();

      return customerAnalyticsMapper.toResponse(params);

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

