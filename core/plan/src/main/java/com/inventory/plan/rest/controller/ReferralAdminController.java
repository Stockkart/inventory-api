package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.domain.model.ReferralAttributionStatus;
import com.inventory.plan.domain.model.ReferralRewardStatus;
import com.inventory.plan.rest.dto.request.AdminReasonRequest;
import com.inventory.plan.rest.dto.request.ReferralApprovalRequest;
import com.inventory.plan.rest.dto.response.AdminReferralAttributionResponse;
import com.inventory.plan.rest.dto.response.AdminReferralRewardResponse;
import com.inventory.plan.service.referral.ReferralAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/referrals")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class ReferralAdminController {

  @Autowired
  private ReferralAdminService adminService;

  /** Defaults to the review queue (PENDING_REVIEW), oldest first. */
  @GetMapping("/attributions")
  public ResponseEntity<ApiResponse<List<AdminReferralAttributionResponse>>> attributions(
      @RequestParam(required = false) ReferralAttributionStatus status) {
    return ResponseEntity.ok(ApiResponse.success(adminService.listAttributions(status)));
  }

  @PostMapping("/attributions/{id}/approve")
  public ResponseEntity<ApiResponse<AdminReferralAttributionResponse>> approve(
      @PathVariable String id, @RequestBody ReferralApprovalRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminService.approveAttribution(
        id, request.getReferrerShopId(), request.getReason(), actor(httpRequest))));
  }

  @PostMapping("/attributions/{id}/reject")
  public ResponseEntity<ApiResponse<AdminReferralAttributionResponse>> reject(
      @PathVariable String id, @RequestBody AdminReasonRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminService.rejectAttribution(id, request.getReason(), actor(httpRequest))));
  }

  /** Newest first; all statuses unless one is given. */
  @GetMapping("/rewards")
  public ResponseEntity<ApiResponse<List<AdminReferralRewardResponse>>> rewards(
      @RequestParam(required = false) ReferralRewardStatus status) {
    return ResponseEntity.ok(ApiResponse.success(adminService.listRewards(status)));
  }

  @PostMapping("/rewards/{id}/approve")
  public ResponseEntity<ApiResponse<AdminReferralRewardResponse>> approveReward(
      @PathVariable String id, @RequestBody AdminReasonRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminService.approveReward(id, request.getReason(), actor(httpRequest))));
  }

  @PostMapping("/rewards/{id}/void")
  public ResponseEntity<ApiResponse<AdminReferralRewardResponse>> voidReward(
      @PathVariable String id, @RequestBody AdminReasonRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminService.voidReward(id, request.getReason(), actor(httpRequest))));
  }

  @PostMapping("/rewards/{id}/clawback")
  public ResponseEntity<ApiResponse<AdminReferralRewardResponse>> clawBack(
      @PathVariable String id, @RequestBody AdminReasonRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(adminService.clawBackReward(id, request.getReason(), actor(httpRequest))));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute("adminId");
  }
}
