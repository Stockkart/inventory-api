package com.inventory.analytics.rest.controller;


import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsItemsResponse;
import com.inventory.analytics.rest.dto.response.InventoryAnalyticsResponse;
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
@Latency(module = "analytics")
@RecordRequestRate(module = "analytics")
@RecordStatusCodes(module = "analytics")
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
   * - listLimit: Rows sent per list (optional, defaults to 50, at most 200); each list's full size
   *   is in listTotals, further pages come from GET /items
   */
  @HeavyRequest(HeavyKind.ANALYTICS)
  @GetMapping
  public ResponseEntity<ApiResponse<InventoryAnalyticsResponse>> getInventoryAnalytics(
      @RequestParam(required = false) Integer lowStockThreshold,
      @RequestParam(required = false) Integer deadStockDays,
      @RequestParam(required = false) Integer expiringSoonDays,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAll,
      @RequestParam(required = false) Integer listLimit,
      HttpServletRequest httpRequest) {

    InventoryAnalyticsResponse response = inventoryAnalyticsService.getInventoryAnalytics(
        shopOf(httpRequest), lowStockThreshold, deadStockDays, expiringSoonDays, includeAll, listLimit);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * One page of one list of the Inventory tab ("Show more").
   *
   * Query Parameters:
   * - list: lowStockItems | notSellingItems | deadStockItems | allItems (required)
   * - page: 0-based page (optional, defaults to 0)
   * - size: rows per page (optional, defaults to 50, at most 200)
   * - lowStockThreshold, deadStockDays, expiringSoonDays: as for the tab
   */
  @HeavyRequest(HeavyKind.ANALYTICS)
  @GetMapping("/items")
  public ResponseEntity<ApiResponse<InventoryAnalyticsItemsResponse>> getInventoryAnalyticsItems(
      @RequestParam String list,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) Integer lowStockThreshold,
      @RequestParam(required = false) Integer deadStockDays,
      @RequestParam(required = false) Integer expiringSoonDays,
      HttpServletRequest httpRequest) {

    InventoryAnalyticsItemsResponse response = inventoryAnalyticsService.getInventoryAnalyticsItems(
        shopOf(httpRequest), list, page, size, lowStockThreshold, deadStockDays, expiringSoonDays);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  private static String shopOf(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(
          ErrorCode.UNAUTHORIZED,
          "Unauthorized access to shop analytics");
    }
    return shopId;
  }
}

