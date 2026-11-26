package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.hr.domain.model.enums.JoinApproveMode;
import com.inventory.hr.domain.model.enums.WorkerJoinStatus;
import com.inventory.hr.rest.dto.request.ApproveJoinRequestRequest;
import com.inventory.hr.rest.dto.request.BulkJoinRequest;
import com.inventory.hr.rest.dto.request.RejectJoinRequestRequest;
import com.inventory.hr.rest.dto.response.BulkJoinResult;
import com.inventory.hr.rest.dto.response.JoinRequestDecision;
import com.inventory.hr.rest.dto.response.JoinRequestDetail;
import com.inventory.hr.rest.dto.response.JoinRequestsPageResponse;
import com.inventory.hr.service.WorkerJoinService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.hr.validation.JoinRequestInboxValidator;
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
 * The manager's Join requests tab (worker-app R4.1–R4.7, R4.10, R11.3, R14.4; design "Manager
 * inbox"). On the Bearer chain and the {@code hr} module like every staff HR screen: {@code
 * RbacModuleInterceptor} does not skip this prefix, so a WORKER login never reaches it, and {@link
 * WorkerJoinService} calls {@code HrPermissionGuard.require(ADD_EDIT_EMPLOYEES)} first in every
 * method, so the permission is checked on every call without a second read here. The caller and
 * the shop come only from the request attributes {@code AuthenticationInterceptor} set; this
 * controller adds the shape checks ({@link JoinRequestInboxValidator}) and nothing else.
 */
@RestController
@RequestMapping("/api/v1/hr/join-requests")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class JoinRequestInboxController {

  @Autowired private WorkerJoinService joinService;

  @Autowired private JoinRequestInboxValidator validator;

  /**
   * One page of the shop's requests, newest first, with the pending count for the tab label (R4.1,
   * R4.2). {@code status} is {@code PENDING} (default) or {@code REJECTED}; {@code size} at most
   * 200 (R11.3).
   */
  @GetMapping
  public ResponseEntity<ApiResponse<JoinRequestsPageResponse>> list(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      HttpServletRequest request) {
    WorkerJoinStatus wanted = validator.requireInboxStatus(status);
    validator.requirePage(page, size);
    return ResponseEntity.ok(ApiResponse.success(joinService.inbox(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request),
        wanted.name(), page, size)));
  }

  /** One request with its possible match (R4.3). */
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<JoinRequestDetail>> detail(
      @PathVariable String id, HttpServletRequest request) {
    validator.requireObjectId(id, "id");
    return ResponseEntity.ok(ApiResponse.success(joinService.detail(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id.trim())));
  }

  /**
   * Approve: {@code CREATE} makes the employee from the snapshot, {@code LINK} links the given one
   * (R4.4, R4.5, R4.8–R4.10). Refusals keep their own code and text and leave the request Pending.
   */
  @PostMapping("/{id}/approve")
  public ResponseEntity<ApiResponse<JoinRequestDecision>> approve(
      @PathVariable String id, @RequestBody ApproveJoinRequestRequest body,
      HttpServletRequest request) {
    validator.requireObjectId(id, "id");
    JoinApproveMode mode = validator.requireApprove(body);
    String employeeId = mode == JoinApproveMode.LINK ? body.getEmployeeId().trim() : null;
    return ResponseEntity.ok(ApiResponse.success(joinService.approve(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id.trim(),
        mode, employeeId)));
  }

  /** Reject with an optional reason of at most 200 characters; blank means none (R4.7). */
  @PostMapping("/{id}/reject")
  public ResponseEntity<ApiResponse<JoinRequestDecision>> reject(
      @PathVariable String id, @RequestBody(required = false) RejectJoinRequestRequest body,
      HttpServletRequest request) {
    validator.requireObjectId(id, "id");
    String reason = body != null ? body.getReason() : null;
    validator.requireReason(reason);
    return ResponseEntity.ok(ApiResponse.success(joinService.reject(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), id.trim(),
        reason)));
  }

  /**
   * "Approve selected" (R4.6, R14.4): each request on its own, with {@code CREATE}; requests with
   * a possible match are skipped and stay Pending. At most 50 ids.
   */
  @PostMapping("/bulk/approve")
  public ResponseEntity<ApiResponse<BulkJoinResult>> bulkApprove(
      @RequestBody BulkJoinRequest body, HttpServletRequest request) {
    List<String> ids = validator.requireBulkIds(body);
    return ResponseEntity.ok(ApiResponse.success(joinService.bulkApprove(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), ids)));
  }

  /** "Reject selected" (R4.6): one optional reason for every selected request. At most 50 ids. */
  @PostMapping("/bulk/reject")
  public ResponseEntity<ApiResponse<BulkJoinResult>> bulkReject(
      @RequestBody BulkJoinRequest body, HttpServletRequest request) {
    List<String> ids = validator.requireBulkIds(body);
    validator.requireReason(body.getReason());
    return ResponseEntity.ok(ApiResponse.success(joinService.bulkReject(
        HrRequestUtils.requireUserId(request), HrRequestUtils.requireShopId(request), ids,
        body.getReason())));
  }
}
