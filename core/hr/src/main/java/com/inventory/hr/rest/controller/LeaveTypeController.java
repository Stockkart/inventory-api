package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.SaveLeaveTypeRequest;
import com.inventory.hr.rest.dto.response.LeaveTypeResponse;
import com.inventory.hr.service.LeaveTypeService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/leave-types")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class LeaveTypeController {

  @Autowired private LeaveTypeService leaveTypeService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<LeaveTypeResponse>>> list(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveTypeService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<LeaveTypeResponse>> create(
      @RequestBody SaveLeaveTypeRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveTypeService.create(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<LeaveTypeResponse>> update(
      @PathVariable String id, @RequestBody SaveLeaveTypeRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveTypeService.update(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id, body)));
  }
}
