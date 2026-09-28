package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.mapper.PlanOrderAdminMapper;
import com.inventory.plan.rest.dto.request.PlanOrderRefundRequest;
import com.inventory.plan.rest.dto.response.AdminPlanOrderResponse;
import com.inventory.plan.service.refund.OrderRefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/plan-orders")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class PlanOrderAdminController {

  @Autowired
  private OrderRefundService orderRefundService;

  @Autowired
  private PlanOrderAdminMapper mapper;

  /** Refunds the order and reverses its grants. Retrying on a REFUNDED order finishes any step left undone. */
  @PostMapping("/{id}/refund")
  public ResponseEntity<ApiResponse<AdminPlanOrderResponse>> refund(
      @PathVariable String id, @RequestBody PlanOrderRefundRequest request, HttpServletRequest httpRequest) {
    boolean issueGatewayRefund = request.getIssueGatewayRefund() == null || request.getIssueGatewayRefund();
    return ResponseEntity.ok(ApiResponse.success(mapper.toResponse(orderRefundService.refundByAdmin(
        id, request.getReason(), issueGatewayRefund, (String) httpRequest.getAttribute("adminId")))));
  }
}
