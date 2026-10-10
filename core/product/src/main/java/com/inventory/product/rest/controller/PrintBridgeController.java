package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.PrintBridgeObservation;
import com.inventory.product.rest.dto.response.PrintBridgeStatusResponse;
import com.inventory.product.service.printing.PrintBridgeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The shop's dot-matrix print bridge: missing, outdated or connected, and where to get it. */
@RestController
@RequestMapping("/api/v1/print-bridge")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class PrintBridgeController {

  @Autowired
  private PrintBridgeService printBridgeService;

  @PostMapping("/status")
  public ResponseEntity<ApiResponse<PrintBridgeStatusResponse>> status(
      @RequestBody PrintBridgeObservation observation, HttpServletRequest httpRequest) {
    requireShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(printBridgeService.status(observation)));
  }

  private static String requireShopId(HttpServletRequest request) {
    String shopId = (String) request.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop context is required");
    }
    return shopId;
  }
}
