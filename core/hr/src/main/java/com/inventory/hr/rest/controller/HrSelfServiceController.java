package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.LeaveDecisionRequest;
import com.inventory.hr.rest.dto.request.RequestLeaveRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.HrNotificationsResponse;
import com.inventory.hr.rest.dto.response.LeavePreviewResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.rest.dto.response.MyLeaveResponse;
import com.inventory.hr.rest.dto.response.MyPayslipResponse;
import com.inventory.hr.service.HrSelfService;
import com.inventory.hr.utils.HrFileResponses;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service (R1.5, R1.6, R6.5, R10.5). {@code /api/v1/hr/me/**} skips the RBAC module check
 * (any member may use it), so every method works on the caller's own data only: the employee is
 * resolved in {@link HrSelfService} from the {@code shopId} and {@code userId} request attributes,
 * never from the path or body. Authentication still applies (no public path).
 */
@RestController
@RequestMapping("/api/v1/hr/me")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class HrSelfServiceController {

  @Autowired private HrSelfService selfService;

  @GetMapping
  public ResponseEntity<ApiResponse<EmployeeResponse>> me(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.me(shop(request), user(request))));
  }

  @GetMapping("/attendance")
  public ResponseEntity<ApiResponse<MonthRegisterResponse>> attendance(
      @RequestParam(required = false) String period, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(
        selfService.attendance(shop(request), user(request), period)));
  }

  @GetMapping("/leave")
  public ResponseEntity<ApiResponse<MyLeaveResponse>> leave(
      @RequestParam(required = false) String year, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(
        selfService.leave(shop(request), user(request), year)));
  }

  @GetMapping("/leave/preview")
  public ResponseEntity<ApiResponse<LeavePreviewResponse>> previewLeave(
      @RequestParam(required = false) String leaveTypeId,
      @RequestParam(required = false) String from, @RequestParam(required = false) String to,
      @RequestParam(required = false) Boolean halfDay, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.previewLeave(shop(request),
        user(request), leaveTypeId, from, to, halfDay)));
  }

  @PostMapping("/leave-requests")
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> requestLeave(
      @RequestBody RequestLeaveRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(
        selfService.requestLeave(shop(request), user(request), body)));
  }

  @PostMapping("/leave-requests/{id}/cancel")
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> cancelLeave(@PathVariable String id,
      @RequestBody(required = false) LeaveDecisionRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.cancelLeave(shop(request),
        user(request), id, body != null ? body.getNote() : null)));
  }

  /** Allowed while the plan is expired (decision 5, PlanExpiryInterceptor). */
  @GetMapping("/payslips")
  public ResponseEntity<ApiResponse<List<MyPayslipResponse>>> payslips(
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(
        selfService.payslips(shop(request), user(request))));
  }

  /** 404 unless the line is the caller's own in a finalised run. */
  @GetMapping("/payslips/{lineId}/pdf")
  public ResponseEntity<byte[]> payslipPdf(@PathVariable String lineId,
      HttpServletRequest request) {
    return HrFileResponses.attachment(selfService.payslipPdf(shop(request), user(request),
        lineId));
  }

  @GetMapping("/notifications")
  public ResponseEntity<ApiResponse<HrNotificationsResponse>> notifications(
      @RequestParam(required = false) String since, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(
        selfService.notifications(shop(request), user(request), since)));
  }

  @PostMapping("/notifications/{id}/read")
  public ResponseEntity<ApiResponse<Map<String, Boolean>>> markRead(@PathVariable String id,
      HttpServletRequest request) {
    selfService.markNotificationRead(shop(request), user(request), id);
    return ResponseEntity.ok(ApiResponse.success(Map.of("read", true)));
  }

  @PostMapping("/notifications/read-all")
  public ResponseEntity<ApiResponse<Map<String, Long>>> markAllRead(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(Map.of("marked",
        selfService.markAllNotificationsRead(shop(request), user(request)))));
  }

  private static String shop(HttpServletRequest request) {
    return HrRequestUtils.requireShopId(request);
  }

  private static String user(HttpServletRequest request) {
    return HrRequestUtils.requireUserId(request);
  }
}
