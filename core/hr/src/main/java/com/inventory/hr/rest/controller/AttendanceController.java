package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.AttendanceHistoryResponse;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.rest.dto.response.MarkUnmarkedPresentResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.service.AttendanceService;
import com.inventory.hr.service.PunchDayService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Attendance registers and marks (R5). Permission checks run in {@link AttendanceService}. */
@RestController
@RequestMapping("/api/v1/hr/attendance")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class AttendanceController {

  @Autowired private AttendanceService attendanceService;

  @Autowired private PunchDayService punchDayService;

  /** VIEW_ATTENDANCE. */
  @GetMapping("/daily")
  public ResponseEntity<ApiResponse<DailyRegisterResponse>> daily(
      @RequestParam(required = false) String asOf, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(attendanceService.daily(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), asOf)));
  }

  /** MARK_ATTENDANCE. */
  @PutMapping("/days")
  public ResponseEntity<ApiResponse<List<AttendanceRowResponse>>> saveDays(
      @RequestBody SaveAttendanceDaysRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(attendanceService.saveDays(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  /** MARK_ATTENDANCE. */
  @PostMapping("/mark-unmarked-present")
  public ResponseEntity<ApiResponse<MarkUnmarkedPresentResponse>> markUnmarkedPresent(
      @RequestBody MarkUnmarkedPresentRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(attendanceService.markUnmarkedPresent(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  /** VIEW_ATTENDANCE. */
  @GetMapping("/month")
  public ResponseEntity<ApiResponse<MonthRegisterResponse>> month(
      @RequestParam String period, @RequestParam(required = false) String employeeId,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(attendanceService.month(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), period,
        employeeId)));
  }

  /** VIEW_ATTENDANCE. One employee's punches of a day, with distance and flags (R3.7). */
  @GetMapping("/punches")
  public ResponseEntity<ApiResponse<List<PunchResponse>>> punches(
      @RequestParam String employeeId, @RequestParam String date, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(punchDayService.listForEmployee(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), employeeId,
        date)));
  }

  /** MARK_ATTENDANCE. */
  @GetMapping("/history")
  public ResponseEntity<ApiResponse<AttendanceHistoryResponse>> history(
      @RequestParam String employeeId, @RequestParam String date, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(attendanceService.history(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), employeeId,
        date)));
  }
}
