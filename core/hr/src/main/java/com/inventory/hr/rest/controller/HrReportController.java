package com.inventory.hr.rest.controller;

import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import com.inventory.hr.service.HrReportService;
import com.inventory.hr.utils.HrFileResponses;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR registers as files (R5.12, R12.1, R12.2, R12.4); permission checks in {@link
 * HrReportService}. Every download runs under the heavy-request limiter kind {@code HR}.
 */
@RestController
@RequestMapping("/api/v1/hr")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class HrReportController {

  @Autowired private HrReportService reportService;

  /** Month attendance register as Excel (VIEW_ATTENDANCE). */
  @HeavyRequest(HeavyKind.HR)
  @GetMapping("/attendance/month/export")
  public ResponseEntity<byte[]> monthExport(@RequestParam(required = false) String period,
      HttpServletRequest request) {
    return HrFileResponses.attachment(reportService.monthExport(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), period));
  }

  /** Leave balances of a leave year as Excel (VIEW_ATTENDANCE). */
  @HeavyRequest(HeavyKind.HR)
  @GetMapping("/leave-balances/export")
  public ResponseEntity<byte[]> leaveBalancesExport(
      @RequestParam(required = false) String year, HttpServletRequest request) {
    return HrFileResponses.attachment(reportService.leaveBalancesExport(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), year));
  }

  /** Salary register of a run, {@code format=xlsx} (default) or {@code pdf} (VIEW_SALARIES). */
  @HeavyRequest(HeavyKind.HR)
  @GetMapping("/payroll-runs/{runId}/register")
  public ResponseEntity<byte[]> salaryRegister(@PathVariable String runId,
      @RequestParam(required = false) String format, HttpServletRequest request) {
    return HrFileResponses.attachment(reportService.salaryRegister(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        format));
  }
}
