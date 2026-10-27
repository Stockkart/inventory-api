package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.LeaveDecisionRequest;
import com.inventory.hr.rest.dto.request.RecordLeaveRequest;
import com.inventory.hr.rest.dto.response.LeavePreviewResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestResponse;
import com.inventory.hr.rest.dto.response.LeaveRequestsPageResponse;
import com.inventory.hr.service.LeaveService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
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
 * Staff leave screens (R6). Permission checks run in {@link LeaveService}; self-service request
 * and cancel endpoints come with {@code /me} (task 10.1).
 */
@RestController
@RequestMapping("/api/v1/hr/leave-requests")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class LeaveController {

  @Autowired private LeaveService leaveService;

  /** VIEW_ATTENDANCE. */
  @GetMapping
  public ResponseEntity<ApiResponse<LeaveRequestsPageResponse>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String employeeId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), status,
        employeeId, page, size)));
  }

  /** MARK_ATTENDANCE: leave recorded for an employee, approved at once. */
  @PostMapping
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> record(
      @RequestBody RecordLeaveRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.record(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  /** MARK_ATTENDANCE. */
  @GetMapping("/preview")
  public ResponseEntity<ApiResponse<LeavePreviewResponse>> preview(
      @RequestParam String employeeId, @RequestParam String leaveTypeId,
      @RequestParam String from, @RequestParam String to,
      @RequestParam(required = false) Boolean halfDay, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.preview(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), employeeId,
        leaveTypeId, from, to, halfDay)));
  }

  /** APPROVE_LEAVE. */
  @PostMapping("/{id}/approve")
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> approve(@PathVariable String id,
      @RequestBody(required = false) LeaveDecisionRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.approve(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id,
        note(body))));
  }

  /** APPROVE_LEAVE. */
  @PostMapping("/{id}/reject")
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> reject(@PathVariable String id,
      @RequestBody(required = false) LeaveDecisionRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.reject(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id,
        note(body))));
  }

  /** APPROVE_LEAVE or MARK_ATTENDANCE. */
  @PostMapping("/{id}/cancel")
  public ResponseEntity<ApiResponse<LeaveRequestResponse>> cancel(@PathVariable String id,
      @RequestBody(required = false) LeaveDecisionRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveService.cancel(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id,
        note(body))));
  }

  private static String note(LeaveDecisionRequest body) {
    return body != null ? body.getNote() : null;
  }
}
