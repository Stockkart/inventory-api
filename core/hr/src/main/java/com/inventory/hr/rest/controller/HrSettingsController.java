package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.UpdateHrSettingsRequest;
import com.inventory.hr.rest.dto.response.HrSettingsResponse;
import com.inventory.hr.rest.dto.response.HrSetupStatusResponse;
import com.inventory.hr.service.HrSettingsService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR settings under {@code /api/v1/hr} rather than {@code /shops/active-shop/...} (design D7, N4
 * deviation), so the one RBAC prefix and the member's HR switch cover them too.
 */
@RestController
@RequestMapping("/api/v1/hr")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class HrSettingsController {

  @Autowired private HrSettingsService hrSettingsService;

  @GetMapping("/settings")
  public ResponseEntity<ApiResponse<HrSettingsResponse>> get(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(hrSettingsService.get(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  @PutMapping("/settings")
  public ResponseEntity<ApiResponse<HrSettingsResponse>> update(
      @RequestBody UpdateHrSettingsRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(hrSettingsService.update(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @GetMapping("/setup-status")
  public ResponseEntity<ApiResponse<HrSetupStatusResponse>> setupStatus(
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(hrSettingsService.setupStatus(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }
}
