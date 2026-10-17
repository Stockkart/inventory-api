package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.gridlayout.GridLayoutService;
import com.inventory.product.rest.dto.request.SaveGridLayoutRequest;
import com.inventory.product.rest.dto.response.GridLayoutResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop-level data grid column layouts (order, widths, hidden columns), shared by every member of
 * the active shop. Same pattern as {@link CardLayoutController}: the body never carries {@code
 * shopId}; the grid comes from the path.
 */
@RestController
@RequestMapping("/api/v1/shops/active-shop/grid-layouts")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class GridLayoutController {

  private final GridLayoutService gridLayoutService;

  public GridLayoutController(GridLayoutService gridLayoutService) {
    this.gridLayoutService = gridLayoutService;
  }

  /** One grid's layout, saved or default, with its column catalog. */
  @GetMapping("/{gridId}")
  public ResponseEntity<ApiResponse<GridLayoutResponse>> get(
      @PathVariable String gridId, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(gridLayoutService.get(shopId, userId, gridId)));
  }

  /** Validates and upserts one grid's layout. */
  @PutMapping("/{gridId}")
  public ResponseEntity<ApiResponse<GridLayoutResponse>> save(
      @PathVariable String gridId,
      @RequestBody SaveGridLayoutRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    log.info("Saving grid layout for shop {} grid {}", shopId, gridId);
    return ResponseEntity.ok(
        ApiResponse.success(gridLayoutService.save(shopId, userId, gridId, request)));
  }

  /** Drops the saved layout and returns the default. */
  @DeleteMapping("/{gridId}")
  public ResponseEntity<ApiResponse<GridLayoutResponse>> reset(
      @PathVariable String gridId, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    log.info("Resetting grid layout for shop {} grid {}", shopId, gridId);
    return ResponseEntity.ok(ApiResponse.success(gridLayoutService.reset(shopId, userId, gridId)));
  }

  private static String requireUserId(HttpServletRequest request) {
    String userId = (String) request.getAttribute("userId");
    if (!StringUtils.hasText(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
    return userId;
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
