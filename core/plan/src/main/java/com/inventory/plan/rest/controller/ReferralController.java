package com.inventory.plan.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import com.inventory.plan.rest.dto.response.ReferralCodeCheckResponse;
import com.inventory.plan.rest.dto.response.ReferralSummaryResponse;
import com.inventory.plan.service.referral.ReferralAttributionService;
import com.inventory.plan.service.referral.ReferralCheckRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/referrals")
@Latency(module = "plan")
@RecordRequestRate(module = "plan")
@RecordStatusCodes(module = "plan")
public class ReferralController {

  @Autowired
  private ReferralAttributionService attributionService;

  @Autowired
  private ReferralCheckRateLimiter rateLimiter;

  /** Used while registering a shop, so the caller may not have a shop yet. Rate-limited per user. */
  @GetMapping("/validate")
  public ResponseEntity<ApiResponse<ReferralCodeCheckResponse>> validate(
      @RequestParam String code, HttpServletRequest httpRequest) {
    String userId = (String) httpRequest.getAttribute("userId");
    rateLimiter.acquire(StringUtils.hasText(userId) ? "user:" + userId : "ip:" + httpRequest.getRemoteAddr());
    return ResponseEntity.ok(ApiResponse.success(attributionService.check(code)));
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<ReferralSummaryResponse>> me(HttpServletRequest httpRequest) {
    String shopId = (String) httpRequest.getAttribute("shopId");
    return ResponseEntity.ok(ApiResponse.success(attributionService.summary(shopId)));
  }
}
