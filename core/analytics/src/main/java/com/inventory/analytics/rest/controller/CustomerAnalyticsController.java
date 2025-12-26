package com.inventory.analytics.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.analytics.rest.dto.customer.CustomerAnalyticsResponse;
import com.inventory.analytics.service.CustomerAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/analytics/customers")
@Slf4j
public class CustomerAnalyticsController {

  @Autowired
  private CustomerAnalyticsService customerAnalyticsService;

  /**
   * Get comprehensive customer analytics.
   * 
   * Query Parameters:
   * - startDate: Start date (ISO 8601 format, optional, defaults to 30 days ago)
   * - endDate: End date (ISO 8601 format, optional, defaults to now)
   * - topN: Number of top customers to return (optional, defaults to 10)
   * - includeAll: Whether to include all customers in response (optional, defaults to false)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<CustomerAnalyticsResponse>> getCustomerAnalytics(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
      @RequestParam(required = false) Integer topN,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAll,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop analytics");
    }

    CustomerAnalyticsResponse response = customerAnalyticsService.getCustomerAnalytics(shopId, startDate, endDate, topN, includeAll);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

