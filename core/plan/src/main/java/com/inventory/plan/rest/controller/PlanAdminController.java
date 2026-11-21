package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.PlanActiveRequest;
import com.inventory.plan.rest.dto.request.PlanAdminRequest;
import com.inventory.plan.rest.dto.response.AdminPlanResponse;
import com.inventory.plan.service.PlanAdminService;
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
import org.springframework.web.bind.annotation.RestController;

/** Admins only: AdminAuthenticationInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/plans")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class PlanAdminController {

  @Autowired
  private PlanAdminService planAdminService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<AdminPlanResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.success(planAdminService.list()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AdminPlanResponse>> create(
      @RequestBody PlanAdminRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(planAdminService.create(request, actor(httpRequest))));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<AdminPlanResponse>> update(
      @PathVariable String id, @RequestBody PlanAdminRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(planAdminService.update(id, request, actor(httpRequest))));
  }

  @PatchMapping("/{id}/active")
  public ResponseEntity<ApiResponse<AdminPlanResponse>> setActive(
      @PathVariable String id, @RequestBody PlanActiveRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(planAdminService.setActive(id, request, actor(httpRequest))));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute("adminId");
  }
}
