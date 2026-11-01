package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.response.WalletResponse;
import com.inventory.plan.service.wallet.WalletService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans/shop/wallet")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class WalletController {

  @Autowired
  private WalletService walletService;

  @GetMapping
  public ResponseEntity<ApiResponse<WalletResponse>> wallet(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(walletService.describe(shopId)));
  }
}
