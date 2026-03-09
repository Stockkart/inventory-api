package com.inventory.analytics.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.analytics.rest.dto.response.SalesAnalyticsResponse;
import com.inventory.analytics.service.SalesAnalyticsService;
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
@RequestMapping("/api/v1/analytics/sales")
@Slf4j
public class SalesAnalyticsController {

  @Autowired
  private SalesAnalyticsService salesAnalyticsService;

  /**
   * Get comprehensive sales analytics.
   * 
   * Query Parameters:
   * - startDate: Start date (ISO 8601 format, optional, defaults to 30 days ago)
   * - endDate: End date (ISO 8601 format, optional, defaults to now)
   * - groupBy: Grouping type - product, lotId, company (optional, returns all if not specified)
   * - timeSeries: Granularity - hour, day, week, month (optional)
   * - topN: Number of top products (optional, defaults to 10)
   * - compare: Whether to compare with previous period (optional, defaults to false)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<SalesAnalyticsResponse>> getSalesAnalytics(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
      @RequestParam(required = false) String groupBy,
      @RequestParam(required = false) String timeSeries,
      @RequestParam(required = false) Integer topN,
      @RequestParam(required = false) Boolean compare,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop analytics");
    }

    SalesAnalyticsResponse response = salesAnalyticsService.getSalesAnalytics(
        shopId, startDate, endDate, groupBy, timeSeries, topN, compare);

    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

