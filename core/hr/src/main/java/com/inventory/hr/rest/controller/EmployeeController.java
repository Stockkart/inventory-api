package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.rest.dto.request.AddSalaryRevisionRequest;
import com.inventory.hr.rest.dto.request.ExitEmployeeRequest;
import com.inventory.hr.rest.dto.request.SaveEmployeeRequest;
import com.inventory.hr.rest.dto.response.EmployeeResponse;
import com.inventory.hr.rest.dto.response.EmployeesPageResponse;
import com.inventory.hr.rest.dto.response.LinkableLoginResponse;
import com.inventory.hr.rest.dto.response.PairingCodeResponse;
import com.inventory.hr.rest.dto.response.PairingStateResponse;
import com.inventory.hr.service.EmployeePairingService;
import com.inventory.hr.service.EmployeeService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Employees and the login picker. Permission checks and masking live in {@link EmployeeService}. */
@RestController
@RequestMapping("/api/v1/hr")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class EmployeeController {

  @Autowired private EmployeeService employeeService;

  @Autowired private EmployeePairingService pairingService;

  /** {@code paired=true|false} lists only employees with / without an active paired phone (R8.12). */
  @GetMapping("/employees")
  public ResponseEntity<ApiResponse<EmployeesPageResponse>> list(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String designation,
      @RequestParam(required = false) String paired,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer size,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.list(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request),
        q, status, designation, paired, page, size)));
  }

  @GetMapping("/employees/designations")
  public ResponseEntity<ApiResponse<List<String>>> designations(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.designations(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  @GetMapping("/employees/{id}")
  public ResponseEntity<ApiResponse<EmployeeResponse>> get(
      @PathVariable String id, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.get(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id)));
  }

  @PostMapping("/employees")
  public ResponseEntity<ApiResponse<EmployeeResponse>> create(
      @RequestBody SaveEmployeeRequest body, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.create(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), body)));
  }

  @PutMapping("/employees/{id}")
  public ResponseEntity<ApiResponse<EmployeeResponse>> update(
      @PathVariable String id, @RequestBody SaveEmployeeRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.update(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id, body)));
  }

  @PostMapping("/employees/{id}/salary-revisions")
  public ResponseEntity<ApiResponse<EmployeeResponse>> addSalaryRevision(
      @PathVariable String id, @RequestBody AddSalaryRevisionRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.addSalaryRevision(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id, body)));
  }

  @PostMapping("/employees/{id}/exit")
  public ResponseEntity<ApiResponse<EmployeeResponse>> exit(
      @PathVariable String id, @RequestBody ExitEmployeeRequest body,
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.exit(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id, body)));
  }

  @GetMapping("/linkable-logins")
  public ResponseEntity<ApiResponse<List<LinkableLoginResponse>>> linkableLogins(
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(employeeService.linkableLogins(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request))));
  }

  // ---- Attendance app pairing (R2, R3.1); ADD_EDIT_EMPLOYEES, checked in the service ----------

  /** The Attendance app section of the employee's page (R2.1, R2.2). */
  @GetMapping("/employees/{id}/pairing")
  public ResponseEntity<ApiResponse<PairingStateResponse>> pairing(
      @PathVariable String id, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(pairingService.state(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id)));
  }

  /** "Pair a phone": a one-time code valid for 15 minutes; cancels the previous unused one. */
  @PostMapping("/employees/{id}/pairing-codes")
  public ResponseEntity<ApiResponse<PairingCodeResponse>> createPairingCode(
      @PathVariable String id, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(pairingService.createCode(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id)));
  }

  /** "Unpair phone": the phone's next punch is refused (R2.12, R3.1). */
  @PostMapping("/employees/{id}/pairing/unpair")
  public ResponseEntity<ApiResponse<PairingStateResponse>> unpair(
      @PathVariable String id, HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(pairingService.unpair(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id)));
  }
}
