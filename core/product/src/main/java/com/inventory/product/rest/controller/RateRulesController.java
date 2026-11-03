package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.PreviewRateRulesRequest;
import com.inventory.product.rest.dto.request.UpdateRateRulesRequest;
import com.inventory.product.rest.dto.response.RateRulesPreviewResponse;
import com.inventory.product.rest.dto.response.RateRulesResponse;
import com.inventory.product.service.RateRulesService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/shops/active-shop/rate-rules")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
public class RateRulesController {

  @Autowired
  private RateRulesService rateRulesService;

  @GetMapping
  public ResponseEntity<ApiResponse<RateRulesResponse>> getRules(HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(rateRulesService.getRules(shopId, userId)));
  }

  @PutMapping
  public ResponseEntity<ApiResponse<RateRulesResponse>> updateRules(
      @RequestBody UpdateRateRulesRequest request, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(rateRulesService.updateRules(shopId, userId, request)));
  }

  @PostMapping("/preview")
  public ResponseEntity<ApiResponse<RateRulesPreviewResponse>> preview(
      @RequestBody(required = false) PreviewRateRulesRequest request,
      HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(rateRulesService.preview(shopId, userId, request)));
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
