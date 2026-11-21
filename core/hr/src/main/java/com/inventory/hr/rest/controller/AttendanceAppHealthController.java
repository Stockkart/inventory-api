package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.response.AppHealthResponse;
import com.inventory.hr.service.AppPunchHealthService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/hr/attendance-app/health}: the manager-facing read of the Attendance app's
 * signature-failure alerts (R10.5). Unlike the rest of {@code /attendance-app/**}, this path stays
 * on the Bearer chain ({@code CorsConfig} excludes exactly {@code
 * AttendanceAppAuthInterceptor.HEALTH_PATH} from the device interceptor and keeps it under {@code
 * AuthenticationInterceptor}), so the caller and shop come from the Bearer attributes like every
 * other HR screen, and the service requires {@code VIEW_ATTENDANCE}.
 */
@RestController
@RequestMapping("/api/v1/hr/attendance-app")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class AttendanceAppHealthController {

  @Autowired private AppPunchHealthService healthService;

  @GetMapping("/health")
  public ResponseEntity<ApiResponse<AppHealthResponse>> health(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(healthService.openAlerts(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }
}
