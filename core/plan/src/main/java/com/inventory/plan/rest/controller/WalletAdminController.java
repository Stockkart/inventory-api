package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.WalletAdjustmentRequest;
import com.inventory.plan.rest.dto.response.WalletResponse;
import com.inventory.plan.service.referral.ReferralAdminService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/wallets")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class WalletAdminController {

  @Autowired
  private ReferralAdminService adminService;

  @GetMapping("/{shopId}")
  public ResponseEntity<ApiResponse<WalletResponse>> wallet(@PathVariable String shopId) {
    return ResponseEntity.ok(ApiResponse.success(adminService.wallet(shopId)));
  }

  @PostMapping("/{shopId}/adjustments")
  public ResponseEntity<ApiResponse<WalletResponse>> adjust(
      @PathVariable String shopId, @RequestBody WalletAdjustmentRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        adminService.adjustWallet(shopId, request, (String) httpRequest.getAttribute("adminId"))));
  }
}
