package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.cardlayout.CardLayoutService;
import com.inventory.product.rest.dto.request.SaveCardLayoutRequest;
import com.inventory.product.rest.dto.response.CardFieldCatalogResponse;
import com.inventory.product.rest.dto.response.CardLayoutsResponse;
import com.inventory.product.rest.dto.response.SurfaceLayoutResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shop-level product card layout endpoints (configurable-product-card Req 1.5, 1.9, 4.1–4.5, 4.8).
 *
 * <p>Every endpoint resolves {@code userId}/{@code shopId} from the request attributes set by the
 * authentication interceptor and fails with {@code UNAUTHORIZED} before touching the service, the
 * same pattern as {@link BarcodeLabelLayoutController}. The body never carries {@code shopId}; the
 * surface comes from the path.
 */
@RestController
@RequestMapping("/api/v1/shops/active-shop/card-layouts")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class CardLayoutController {

  private final CardLayoutService cardLayoutService;

  public CardLayoutController(CardLayoutService cardLayoutService) {
    this.cardLayoutService = cardLayoutService;
  }

  /** Every surface the shop can configure, saved or default (Req 4.4). */
  @GetMapping
  public ResponseEntity<ApiResponse<CardLayoutsResponse>> getAll(HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(cardLayoutService.getAll(shopId, userId)));
  }

  /** Card-usable catalog fields, surfaces and editor limits (Req 1.5). */
  @GetMapping("/field-catalog")
  public ResponseEntity<ApiResponse<CardFieldCatalogResponse>> fieldCatalog(
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(cardLayoutService.fieldCatalog(shopId, userId)));
  }

  /** One surface, saved or default (Req 4.2, 4.3). */
  @GetMapping("/{surfaceId}")
  public ResponseEntity<ApiResponse<SurfaceLayoutResponse>> get(
      @PathVariable String surfaceId, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(cardLayoutService.get(shopId, userId, surfaceId)));
  }

  /** The built-in layouts for the shop's vertical; never persists (Req 4.5). */
  @GetMapping("/{surfaceId}/defaults")
  public ResponseEntity<ApiResponse<SurfaceLayoutResponse>> defaults(
      @PathVariable String surfaceId, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(cardLayoutService.defaults(shopId, userId, surfaceId)));
  }

  /** Validates and upserts one surface's layouts (Req 4.1). */
  @PutMapping("/{surfaceId}")
  public ResponseEntity<ApiResponse<SurfaceLayoutResponse>> save(
      @PathVariable String surfaceId,
      @RequestBody SaveCardLayoutRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    log.info("Saving card layout for shop {} surface {}", shopId, surfaceId);
    return ResponseEntity.ok(
        ApiResponse.success(cardLayoutService.save(shopId, userId, surfaceId, request)));
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
