package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.VoucherActiveRequest;
import com.inventory.plan.rest.dto.request.VoucherGenerateRequest;
import com.inventory.plan.rest.dto.request.VoucherUpdateRequest;
import com.inventory.plan.rest.dto.response.AdminVoucherResponse;
import com.inventory.plan.rest.dto.response.VoucherRedemptionResponse;
import com.inventory.plan.service.voucher.VoucherAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/vouchers")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class VoucherAdminController {

  @Autowired
  private VoucherAdminService voucherAdminService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<AdminVoucherResponse>>> list(
      @RequestParam(required = false) String addOnCode) {
    return ResponseEntity.ok(ApiResponse.success(voucherAdminService.list(addOnCode)));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<List<AdminVoucherResponse>>> generate(
      @RequestBody VoucherGenerateRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(voucherAdminService.generate(request, actor(httpRequest))));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<AdminVoucherResponse>> update(
      @PathVariable String id, @RequestBody VoucherUpdateRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(voucherAdminService.update(id, request, actor(httpRequest))));
  }

  @PatchMapping("/{id}/active")
  public ResponseEntity<ApiResponse<AdminVoucherResponse>> setActive(
      @PathVariable String id, @RequestBody VoucherActiveRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(voucherAdminService.setActive(id, request, actor(httpRequest))));
  }

  @GetMapping("/{id}/redemptions")
  public ResponseEntity<ApiResponse<List<VoucherRedemptionResponse>>> redemptions(@PathVariable String id) {
    return ResponseEntity.ok(ApiResponse.success(voucherAdminService.redemptions(id)));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute("adminId");
  }
}
