package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.heavy.HeavyKind;
import com.inventory.common.heavy.HeavyRequest;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.EmailPayslipsRequest;
import com.inventory.hr.rest.dto.request.ReasonRequest;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import com.inventory.hr.rest.dto.response.EmailPayslipsResponse;
import com.inventory.hr.rest.dto.response.PayrollLineResponse;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse;
import com.inventory.hr.rest.dto.response.UnmarkedDaysResponse;
import com.inventory.hr.service.PayrollService;
import com.inventory.hr.service.PayslipService;
import com.inventory.hr.service.SalaryPaymentService;
import com.inventory.hr.utils.HrFileResponses;
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

/**
 * Payroll runs (R8, R11.1), salary payments (R9) and payslips (R10). Reads need RUN_PAYROLL or
 * RECORD_PAYMENTS, every change RUN_PAYROLL, payments RECORD_PAYMENTS, payslips VIEW_SALARIES;
 * the checks run in the services. Shop and caller come from the request attributes only.
 * Create, recalculate and finalise run under the heavy-request limiter kind {@code HR}.
 */
@RestController
@RequestMapping("/api/v1/hr/payroll-runs")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class PayrollController {

  @Autowired private PayrollService payrollService;

  @Autowired private SalaryPaymentService salaryPaymentService;

  @Autowired private PayslipService payslipService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<PayrollRunResponse>>> list(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  @HeavyRequest(HeavyKind.HR)
  @PostMapping
  public ResponseEntity<ApiResponse<PayrollRunResponse>> create(
      @RequestBody CreatePayrollRunRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.create(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @GetMapping("/{runId}")
  public ResponseEntity<ApiResponse<PayrollRunResponse>> get(@PathVariable String runId,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.get(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId)));
  }

  @HeavyRequest(HeavyKind.HR)
  @PostMapping("/{runId}/recalculate")
  public ResponseEntity<ApiResponse<PayrollRunResponse>> recalculate(@PathVariable String runId,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.recalculate(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId)));
  }

  @PutMapping("/{runId}/lines/{lineId}/adjustments")
  public ResponseEntity<ApiResponse<PayrollLineResponse>> saveAdjustments(
      @PathVariable String runId, @PathVariable String lineId,
      @RequestBody SavePayrollAdjustmentsRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.saveAdjustments(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        lineId, body)));
  }

  @GetMapping("/{runId}/unmarked")
  public ResponseEntity<ApiResponse<UnmarkedDaysResponse>> unmarked(@PathVariable String runId,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.unmarked(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId)));
  }

  @HeavyRequest(HeavyKind.HR)
  @PostMapping("/{runId}/finalise")
  public ResponseEntity<ApiResponse<PayrollRunResponse>> finalise(@PathVariable String runId,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.finalise(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId)));
  }

  @PostMapping("/{runId}/reopen")
  public ResponseEntity<ApiResponse<PayrollRunResponse>> reopen(@PathVariable String runId,
      @RequestBody(required = false) ReasonRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payrollService.reopen(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        body != null ? body.getReason() : null)));
  }

  // ---- payments and payslips (task 9) ----------------------------------------------------------

  /** Pay ≤ 50 lines as one batch with one journal (RECORD_PAYMENTS, R9.1–R9.3). */
  @PostMapping("/{runId}/payments")
  public ResponseEntity<ApiResponse<SalaryPaymentsResponse>> pay(@PathVariable String runId,
      @RequestBody RecordSalaryPaymentsRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(salaryPaymentService.pay(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        body)));
  }

  /** Undo one paid line with a compensating journal (RECORD_PAYMENTS, R9.5). */
  @PostMapping("/{runId}/lines/{lineId}/payment/undo")
  public ResponseEntity<ApiResponse<PayrollLineResponse>> undoPayment(@PathVariable String runId,
      @PathVariable String lineId, @RequestBody(required = false) ReasonRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(salaryPaymentService.undo(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        lineId, body != null ? body.getReason() : null)));
  }

  /** One payslip PDF of a FINALISED run (VIEW_SALARIES, R10.1–R10.3). */
  @GetMapping("/{runId}/lines/{lineId}/payslip")
  public ResponseEntity<byte[]> payslip(@PathVariable String runId, @PathVariable String lineId,
      HttpServletRequest request) {
    return HrFileResponses.attachment(payslipService.staffPayslip(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        lineId));
  }

  /** Queue payslip emails for ≤ 300 lines (RUN_PAYROLL, R10.4). */
  @PostMapping("/{runId}/payslips/email")
  public ResponseEntity<ApiResponse<EmailPayslipsResponse>> emailPayslips(
      @PathVariable String runId, @RequestBody EmailPayslipsRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(payslipService.email(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), runId,
        body)));
  }
}
