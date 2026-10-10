package com.inventory.product.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.product.rest.dto.request.CreatePrintJobRequest;
import com.inventory.product.rest.dto.request.ReportPrintOutcomeRequest;
import com.inventory.product.rest.dto.response.PrintJobResponse;
import com.inventory.product.rest.dto.response.PrintOutcomeResponse;
import com.inventory.product.service.printing.PrintJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Dot-matrix print jobs: how to print a document, and what a reported print means. */
@RestController
@RequestMapping("/api/v1/print-jobs")
@Latency(module = "product")
@RecordRequestRate(module = "product")
@RecordStatusCodes(module = "product")
@Slf4j
public class PrintJobController {

  @Autowired
  private PrintJobService printJobService;

  @PostMapping
  public ResponseEntity<ApiResponse<PrintJobResponse>> create(
      @RequestBody CreatePrintJobRequest request, HttpServletRequest httpRequest) {
    String userId = requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(printJobService.create(shopId, userId, request)));
  }

  @PostMapping("/{printJobId}/outcome")
  public ResponseEntity<ApiResponse<PrintOutcomeResponse>> reportOutcome(
      @PathVariable String printJobId,
      @RequestBody ReportPrintOutcomeRequest request,
      HttpServletRequest httpRequest) {
    requireUserId(httpRequest);
    String shopId = requireShopId(httpRequest);
    return ResponseEntity.ok(
        ApiResponse.success(printJobService.reportOutcome(shopId, printJobId, request)));
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
