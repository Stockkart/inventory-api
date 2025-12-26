package com.inventory.analytics.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.analytics.rest.dto.vendor.VendorAnalyticsResponse;
import com.inventory.analytics.service.VendorAnalyticsService;
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
@RequestMapping("/api/v1/analytics/vendors")
@Slf4j
public class VendorAnalyticsController {

  @Autowired
  private VendorAnalyticsService vendorAnalyticsService;

  /**
   * Get comprehensive vendor analytics.
   * 
   * Query Parameters:
   * - startDate: Start date for revenue calculations (ISO 8601 format, optional, defaults to 30 days ago)
   * - endDate: End date for revenue calculations (ISO 8601 format, optional, defaults to now)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<VendorAnalyticsResponse>> getVendorAnalytics(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop analytics");
    }

    VendorAnalyticsResponse response = vendorAnalyticsService.getVendorAnalytics(shopId, startDate, endDate);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

