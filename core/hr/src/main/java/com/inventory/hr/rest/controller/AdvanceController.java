package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.ReasonRequest;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.response.AdvanceResponse;
import com.inventory.hr.rest.dto.response.AdvanceStatementResponse;
import com.inventory.hr.service.AdvanceService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Salary advances (R7). Every endpoint needs MANAGE_ADVANCES, checked in {@link AdvanceService};
 * shop and caller come from the request attributes only. The statement sits under the employee
 * path, hence the bare {@code /api/v1/hr} prefix.
 */
@RestController
@RequestMapping("/api/v1/hr")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class AdvanceController {

  @Autowired private AdvanceService advanceService;

  @GetMapping("/advances")
  public ResponseEntity<ApiResponse<List<AdvanceResponse>>> list(
      @RequestParam(required = false) String employeeId, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(advanceService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request),
        employeeId)));
  }

  @PostMapping("/advances")
  public ResponseEntity<ApiResponse<AdvanceResponse>> record(
      @RequestBody RecordAdvanceRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(advanceService.record(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @PostMapping("/advances/{id}/cancel")
  public ResponseEntity<ApiResponse<AdvanceResponse>> cancel(@PathVariable String id,
      @RequestBody(required = false) ReasonRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(advanceService.cancel(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id,
        body != null ? body.getReason() : null)));
  }

  @GetMapping("/employees/{id}/advance-statement")
  public ResponseEntity<ApiResponse<AdvanceStatementResponse>> statement(@PathVariable String id,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(advanceService.statement(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id)));
  }
}
