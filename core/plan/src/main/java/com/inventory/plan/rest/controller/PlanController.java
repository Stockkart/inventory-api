package com.inventory.plan.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.request.RecordUsageRequest;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.rest.dto.response.PlanTransactionResponse;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.rest.dto.response.UsageResponse;
import com.inventory.plan.service.PlanService;
import com.inventory.plan.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

  @Autowired
  private PlanService planService;

  @Autowired
  private UsageService usageService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<PlanResponse>>> listPlans() {
    return ResponseEntity.ok(ApiResponse.success(planService.listPlans()));
  }

  @GetMapping("/{planId}")
  public ResponseEntity<ApiResponse<PlanResponse>> getPlan(@PathVariable String planId) {
    return ResponseEntity.ok(ApiResponse.success(planService.getPlan(planId)));
  }

  @GetMapping("/shop/status")
  public ResponseEntity<ApiResponse<ShopPlanStatusResponse>> getShopPlanStatus(HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planService.getShopPlanStatus(shopId)));
  }

  @GetMapping("/shop/{shopId}/suggested")
  public ResponseEntity<ApiResponse<PlanResponse>> getSuggestedPlan(
      @PathVariable String shopId,
      HttpServletRequest httpRequest) {
    requireAuth(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planService.getSuggestedPlan(shopId)));
  }

  @PostMapping("/shop/{shopId}/assign")
  public ResponseEntity<ApiResponse<PlanResponse>> assignPlan(
      @PathVariable String shopId,
      @RequestBody AssignPlanRequest request,
      HttpServletRequest httpRequest) {
    requireAuth(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planService.assignPlan(shopId, request)));
  }

  @GetMapping("/shop/transactions")
  public ResponseEntity<ApiResponse<List<PlanTransactionResponse>>> listPlanTransactions(HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planService.listPlanTransactions(shopId)));
  }

  @GetMapping("/shop/usage")
  public ResponseEntity<ApiResponse<UsageResponse>> getCurrentUsage(HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(usageService.getCurrentUsage(shopId)));
  }

  @PutMapping("/shop/usage")
  public ResponseEntity<ApiResponse<UsageResponse>> recordUsage(
      @RequestBody RecordUsageRequest request,
      HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(usageService.recordUsage(shopId, request)));
  }

  private void requireAuth(HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
  }

  private String getShopId(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not selected or user not authenticated");
    }
    return shopId;
  }
}
