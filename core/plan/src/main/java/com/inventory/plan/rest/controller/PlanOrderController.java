package com.inventory.plan.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.QuoteRequest;
import com.inventory.plan.rest.dto.response.PlanOrderStatusResponse;
import com.inventory.plan.rest.dto.response.QuoteResponse;
import com.inventory.plan.service.OrderPricingService;
import com.inventory.plan.service.PlanPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans/orders")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class PlanOrderController {

  @Autowired
  private OrderPricingService orderPricingService;

  @Autowired
  private PlanPaymentService planPaymentService;

  @PostMapping("/quote")
  public ResponseEntity<ApiResponse<QuoteResponse>> quote(@RequestBody QuoteRequest request,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(orderPricingService.quote(shopId, request)));
  }

  /** Cancels the caller's own unpaid order (e.g. the payment window was closed) and frees its holds. */
  @PostMapping("/{orderId}/cancel")
  public ResponseEntity<ApiResponse<PlanOrderStatusResponse>> cancel(@PathVariable String orderId,
      HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (!StringUtils.hasText(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not selected or user not authenticated");
    }
    return ResponseEntity.ok(ApiResponse.success(planPaymentService.cancelOrder(shopId, orderId)));
  }
}
