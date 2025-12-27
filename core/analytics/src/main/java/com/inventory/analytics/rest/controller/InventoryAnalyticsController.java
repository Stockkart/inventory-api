package com.inventory.analytics.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.analytics.rest.dto.inventory.InventoryAnalyticsResponse;
import com.inventory.analytics.service.InventoryAnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics/inventory")
@Slf4j
public class InventoryAnalyticsController {

  @Autowired
  private InventoryAnalyticsService inventoryAnalyticsService;

  /**
   * Get comprehensive inventory analytics.
   * 
   * Query Parameters:
   * - lowStockThreshold: Threshold count for low stock alert (optional, defaults to 20% of received)
   * - deadStockDays: Days without sales to consider dead stock (optional, defaults to 90)
   * - expiringSoonDays: Days until expiry to alert (optional, defaults to 30)
   * - includeAll: Whether to include all items in response (optional, defaults to false)
   */
  @GetMapping
  public ResponseEntity<ApiResponse<InventoryAnalyticsResponse>> getInventoryAnalytics(
      @RequestParam(required = false) Integer lowStockThreshold,
      @RequestParam(required = false) Integer deadStockDays,
      @RequestParam(required = false) Integer expiringSoonDays,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAll,
      HttpServletRequest httpRequest) {

    String shopId = (String) httpRequest.getAttribute("shopId");

    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop analytics");
    }

    InventoryAnalyticsResponse response = inventoryAnalyticsService.getInventoryAnalytics(
        shopId, lowStockThreshold, deadStockDays, expiringSoonDays, includeAll);
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}

