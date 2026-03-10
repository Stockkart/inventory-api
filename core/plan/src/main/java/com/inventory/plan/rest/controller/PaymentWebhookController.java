package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.plan.mapper.PlanMapper;
import com.inventory.plan.rest.dto.request.AssignPlanRequest;
import com.inventory.plan.rest.dto.request.PaymentWebhookPayload;
import com.inventory.plan.rest.dto.response.PlanResponse;
import com.inventory.plan.service.PlanService;
import com.inventory.plan.validation.PlanValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plans")
@Slf4j
public class PaymentWebhookController {

  @Autowired
  private PlanService planService;

  @Autowired
  private PlanValidator planValidator;

  @Autowired
  private PlanMapper planMapper;

  @PostMapping("/webhook/payment-success")
  public ResponseEntity<ApiResponse<PlanResponse>> onPaymentSuccess(
      @RequestBody PaymentWebhookPayload payload) {
    planValidator.validatePaymentWebhookPayload(payload);
    log.info("Payment webhook received for shop: {}, plan: {}, paymentId: {}",
        payload.getShopId(), payload.getPlanId(), payload.getPaymentId());

    AssignPlanRequest request = planMapper.toAssignPlanRequest(payload);
    PlanResponse result = planService.assignPlan(payload.getShopId(), request);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
