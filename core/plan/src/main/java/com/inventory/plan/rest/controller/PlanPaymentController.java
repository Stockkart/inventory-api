package com.inventory.plan.rest.controller;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.AuthenticationException;
import com.inventory.plan.rest.dto.request.CreatePlanCheckoutRequest;
import com.inventory.plan.rest.dto.request.VerifyPlanPaymentRequest;
import com.inventory.plan.rest.dto.response.PaymentConfigResponse;
import com.inventory.plan.rest.dto.response.PlanCheckoutResponse;
import com.inventory.plan.rest.dto.response.VerifyPlanPaymentResponse;
import com.inventory.plan.service.PlanPaymentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plans/payment")
public class PlanPaymentController {

  @Autowired
  private PlanPaymentService planPaymentService;

  @GetMapping("/config")
  public ResponseEntity<ApiResponse<PaymentConfigResponse>> getPaymentConfig() {
    return ResponseEntity.ok(ApiResponse.success(planPaymentService.getPaymentConfig()));
  }

  @PostMapping("/checkout")
  public ResponseEntity<ApiResponse<PlanCheckoutResponse>> createCheckout(
      @RequestBody CreatePlanCheckoutRequest request,
      HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planPaymentService.createCheckout(shopId, request)));
  }

  @PostMapping("/verify")
  public ResponseEntity<ApiResponse<VerifyPlanPaymentResponse>> verifyPayment(
      @RequestBody VerifyPlanPaymentRequest request,
      HttpServletRequest httpRequest) {
    String shopId = getShopId(httpRequest);
    return ResponseEntity.ok(ApiResponse.success(planPaymentService.verifyPayment(shopId, request)));
  }

  @PostMapping("/webhook/{provider}")
  public ResponseEntity<ApiResponse<String>> handleWebhook(
      @PathVariable String provider,
      @RequestBody String rawBody,
      HttpServletRequest httpRequest) {
    Map<String, String> headers = extractHeaders(httpRequest);
    planPaymentService.handleProviderWebhook(provider, rawBody, headers);
    return ResponseEntity.ok(ApiResponse.success("ok"));
  }

  private String getShopId(HttpServletRequest httpRequest) {
    requireAuth(httpRequest);
    String shopId = (String) httpRequest.getAttribute("shopId");
    if (StringUtils.isEmpty(shopId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "Shop not selected or user not authenticated");
    }
    return shopId;
  }

  private void requireAuth(HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    if (StringUtils.isEmpty(userId)) {
      throw new AuthenticationException(ErrorCode.UNAUTHORIZED, "User not authenticated");
    }
  }

  private static Map<String, String> extractHeaders(HttpServletRequest request) {
    Enumeration<String> names = request.getHeaderNames();
    if (names == null) {
      return Collections.emptyMap();
    }
    Map<String, String> headers = new HashMap<>();
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      headers.put(name, request.getHeader(name));
    }
    return headers;
  }
}
