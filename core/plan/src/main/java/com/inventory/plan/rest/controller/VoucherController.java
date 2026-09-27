package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.response.VoucherCheckResponse;
import com.inventory.plan.service.voucher.VoucherService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans/vouchers")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class VoucherController {

  @Autowired
  private VoucherService voucherService;

  /** Lightweight "is this code real" check. Consumes nothing; the quote prices the cart. */
  @GetMapping("/validate")
  public ResponseEntity<ApiResponse<VoucherCheckResponse>> validate(
      @RequestParam String code, HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(voucherService.check(code, shopId)));
  }
}
