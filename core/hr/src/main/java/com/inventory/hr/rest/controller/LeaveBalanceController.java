package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.response.LeaveBalanceRow;
import com.inventory.hr.service.LeaveBalanceService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Leave balances (R6.3, R12.4); the Excel export is task 9.5. */
@RestController
@RequestMapping("/api/v1/hr/leave-balances")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class LeaveBalanceController {

  @Autowired private LeaveBalanceService leaveBalanceService;

  /**
   * VIEW_ATTENDANCE; {@code year} like {@code 2025} or {@code 2025-26}, default the current one;
   * optional {@code employeeId} returns that employee's row only.
   */
  @GetMapping
  public ResponseEntity<ApiResponse<List<LeaveBalanceRow>>> list(
      @RequestParam(required = false) String year,
      @RequestParam(required = false) String employeeId, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(leaveBalanceService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), year,
        employeeId)));
  }
}
