package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.response.PlanMisResponse;
import com.inventory.plan.service.mis.PlanMisService;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform admins only: PlatformRoleInterceptor guards /api/v1/admin/**. */
@RestController
@RequestMapping("/api/v1/admin/mis")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class PlanMisAdminController {

  @Autowired
  private PlanMisService misService;

  /** Both days inclusive (Asia/Kolkata); defaults to the last 30 days, at most 366. */
  @GetMapping("/plans")
  public ResponseEntity<ApiResponse<PlanMisResponse>> plans(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ResponseEntity.ok(ApiResponse.success(misService.report(from, to)));
  }
}
