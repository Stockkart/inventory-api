package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.plan.rest.dto.plan.AssignPlanRequest;
import com.inventory.plan.validation.PlanValidator;
import com.inventory.plan.rest.dto.plan.PaymentWebhookPayload;
import com.inventory.plan.rest.dto.plan.PlanResponse;
import com.inventory.plan.service.PlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives payment success webhooks from payment gateways.
 * Assigns plan to shop automatically when payment is confirmed.
 * This endpoint should be called by the payment provider (Razorpay, Stripe, etc.)
 * on successful payment - not by the frontend directly.
 */
@RestController
@RequestMapping("/api/v1/plans")
@Slf4j
public class PaymentWebhookController {

  @Autowired
  private PlanService planService;

  @Autowired
  private PlanValidator planValidator;

  /**
   * Webhook for payment success. Assigns plan to shop.
   * Add this URL to your payment gateway's webhook configuration.
   */
  @PostMapping("/webhook/payment-success")
  public ResponseEntity<ApiResponse<PlanResponse>> onPaymentSuccess(
      @RequestBody PaymentWebhookPayload payload) {
    planValidator.validatePaymentWebhookPayload(payload);
    log.info("Payment webhook received for shop: {}, plan: {}, paymentId: {}",
        payload.getShopId(), payload.getPlanId(), payload.getPaymentId());

    AssignPlanRequest request = new AssignPlanRequest();
    request.setPlanId(payload.getPlanId());
    request.setDurationMonths(payload.getDurationMonths() != null ? payload.getDurationMonths() : 12);
    request.setPaymentMethod(payload.getPaymentMethod() != null ? payload.getPaymentMethod() : "CARD");

    PlanResponse result = planService.assignPlan(payload.getShopId(), request);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
