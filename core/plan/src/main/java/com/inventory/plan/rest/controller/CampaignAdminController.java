package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.request.CampaignActiveRequest;
import com.inventory.plan.rest.dto.request.CampaignRequest;
import com.inventory.plan.rest.dto.response.AdminCampaignResponse;
import com.inventory.plan.service.CampaignAdminService;
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
@RequestMapping("/api/v1/admin/campaigns")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class CampaignAdminController {

  @Autowired
  private CampaignAdminService campaignAdminService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<AdminCampaignResponse>>> list() {
    return ResponseEntity.ok(ApiResponse.success(campaignAdminService.list()));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<AdminCampaignResponse>> create(
      @RequestBody CampaignRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        campaignAdminService.create(request, actor(httpRequest))));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<AdminCampaignResponse>> update(
      @PathVariable String id, @RequestBody CampaignRequest request, HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        campaignAdminService.update(id, request, actor(httpRequest))));
  }

  @PatchMapping("/{id}/active")
  public ResponseEntity<ApiResponse<AdminCampaignResponse>> setActive(
      @PathVariable String id, @RequestBody CampaignActiveRequest request,
      HttpServletRequest httpRequest) {
    return ResponseEntity.ok(ApiResponse.success(
        campaignAdminService.setActive(id, request, actor(httpRequest))));
  }

  private static String actor(HttpServletRequest request) {
    return (String) request.getAttribute("adminId");
  }
}
