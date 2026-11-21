package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.AdminPlanGrantRequest;
import com.inventory.plan.rest.dto.response.ShopPlanStatusResponse;
import com.inventory.plan.service.ShopPlanGrantService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/shops")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class ShopPlanAdminController {

  @Autowired
  private ShopPlanGrantService shopPlanGrantService;

  @PostMapping("/{shopId}/plan-grants")
  public ResponseEntity<ApiResponse<ShopPlanStatusResponse>> grantPlan(
      @PathVariable String shopId, @RequestBody AdminPlanGrantRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        shopPlanGrantService.grant(shopId, request, (String) httpRequest.getAttribute("adminId"))));
  }
}
