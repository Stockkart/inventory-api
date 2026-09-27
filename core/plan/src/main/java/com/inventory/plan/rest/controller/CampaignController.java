package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.response.CampaignResponse;
import com.inventory.plan.service.CampaignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public: the banner shows on the landing page too, before login. */
@RestController
@RequestMapping("/api/v1/campaigns")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class CampaignController {

  @Autowired
  private CampaignService campaignService;

  /** Data is null when no campaign should show. Not cached: state changes at exact instants. */
  @GetMapping("/active")
  public ResponseEntity<ApiResponse<CampaignResponse>> active() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(ApiResponse.success(campaignService.findActive().orElse(null)));
  }
}
