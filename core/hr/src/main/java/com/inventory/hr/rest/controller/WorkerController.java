package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.rest.dto.request.AbandonPairingRequest;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.request.CreateJoinRequestRequest;
import com.inventory.hr.rest.dto.request.UpdateWorkerProfileRequest;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.HrAccountNotificationsResponse;
import com.inventory.hr.rest.dto.response.JoinRequestSelfResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.ShopLookupResponse;
import com.inventory.hr.rest.dto.response.WorkerProfileResponse;
import com.inventory.hr.rest.dto.response.WorkerStateResponse;
import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.WorkerJoinService;
import com.inventory.hr.service.WorkerPairingService;
import com.inventory.hr.service.WorkerSelfService;
import com.inventory.hr.service.WorkerStateService;
import com.inventory.hr.utils.HrRequestUtils;
import com.inventory.hr.validation.JoinRequestInboxValidator;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Everything the Stockkart Team app does with its login, {@code /api/v1/hr/worker/**}
 * (worker-app design DD3, "Worker endpoints"). On the Bearer chain: {@code AuthenticationInterceptor}
 * sets the caller; {@code RbacModuleInterceptor} skips this prefix like {@code /hr/me} because a
 * WORKER membership resolves to every module off (DD5); {@code PlanExpiryInterceptor} exempts it so
 * a worker's Home keeps working while the shop's plan is expired. {@code WorkerCredentialInterceptor}
 * runs ahead of all three and refuses a request that also carries a device-envelope header (R9.1).
 *
 * <p>Every handler derives the account from the token ({@link HrRequestUtils#requireUserId}) and
 * never reads an employee, shop or user id from the path, query or body (R8.4, R11.2); the one
 * exception is the shop the worker picked for a join request, which names a shop, not a person.
 * Every handler but {@code GET /me} first applies {@link WorkerSelfService#requireWorkerAccess}:
 * a staff account without an employee gets {@code HR_WORKER_ONLY} everywhere else (R5.6).
 *
 * <p>This controller adds the request-shape checks (400s) and nothing else; every business refusal
 * is a service's and keeps its own code so the app shows the message word for word. The pairing
 * handshake is the only one (worker-app DD4): it takes the HR-module bodies and runs under the
 * per-installation lockout ({@link AppRateLimiter#guardCodeAttempt}, HR R2.5).
 */
@RestController
@RequestMapping("/api/v1/hr/worker")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class WorkerController {

  static final int MAX_EMAIL_LENGTH = 254;

  @Autowired private WorkerStateService stateService;

  @Autowired private WorkerJoinService joinService;

  @Autowired private WorkerPairingService pairingService;

  @Autowired private WorkerSelfService selfService;

  @Autowired private AppRateLimiter rateLimiter;

  @Autowired private JoinRequestInboxValidator validator;

  // ---- state (R6.1, R3.10, R10.1) -----------------------------------------------------------------

  /** The one answer the app routes on. Any role: a staff account without an employee is told so. */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<WorkerStateResponse>> me(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(stateService.state(user(request))));
  }

  // ---- join flow (R3.2–R3.8) ------------------------------------------------------------------

  /** The shops an owner e-mail owns; an empty list for every kind of miss (R3.3, R11.4). 10/h. */
  @GetMapping("/shops")
  public ResponseEntity<ApiResponse<ShopLookupResponse>> lookupShops(
      @RequestParam(required = false) String ownerEmail, HttpServletRequest request) {
    String userId = worker(request);
    if (!StringUtils.hasText(ownerEmail)) {
      throw new ValidationException("ownerEmail is required");
    }
    if (ownerEmail.length() > MAX_EMAIL_LENGTH) {
      throw new ValidationException("ownerEmail must be at most " + MAX_EMAIL_LENGTH
          + " characters");
    }
    return ResponseEntity.ok(ApiResponse.success(joinService.lookupShops(userId, ownerEmail)));
  }

  /** "Yes, this is my shop" (R3.4, R3.7): one pending request per account, 5 a day. */
  @PostMapping("/join-requests")
  public ResponseEntity<ApiResponse<JoinRequestSelfResponse>> createJoinRequest(
      @RequestBody(required = false) CreateJoinRequestRequest body, HttpServletRequest request) {
    String userId = worker(request);
    if (body == null || !StringUtils.hasText(body.getShopId())) {
      throw new ValidationException("shopId is required");
    }
    return ResponseEntity.ok(ApiResponse.success(
        joinService.create(userId, body.getShopId().trim())));
  }

  /** "Withdraw request" (R3.6): own, Pending only. */
  @PostMapping("/join-requests/{id}/withdraw")
  public ResponseEntity<ApiResponse<JoinRequestSelfResponse>> withdraw(
      @PathVariable String id, HttpServletRequest request) {
    String userId = worker(request);
    validator.requireObjectId(id, "id");
    return ResponseEntity.ok(ApiResponse.success(joinService.withdraw(userId, id.trim())));
  }

  // ---- pairing with a login (R7.2–R7.5): the only handshake (DD4) ----------------------------

  /**
   * Step one of the handshake with the caller's identity (R7.2, R7.3): the code must be for the
   * caller's own employee. The HR-module body and lockout.
   */
  @PostMapping("/pairings/claim")
  public ResponseEntity<ApiResponse<ClaimPairingResponse>> claim(
      @RequestBody(required = false) ClaimPairingRequest body, HttpServletRequest request) {
    String userId = worker(request);
    AttendanceAppController.validate(body);
    ClaimPairingResponse response = rateLimiter.guardCodeAttempt(body.getInstallationId(),
        () -> pairingService.claim(userId, body));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /** Step two, "I agree", with the caller's identity (R7.2, R7.3). */
  @PostMapping("/pairings/complete")
  public ResponseEntity<ApiResponse<PairingBootstrapResponse>> complete(
      @RequestBody(required = false) CompletePairingRequest body, HttpServletRequest request) {
    String userId = worker(request);
    AttendanceAppController.validate(body);
    PairingBootstrapResponse response = rateLimiter.guardCodeAttempt(body.getInstallationId(),
        () -> pairingService.complete(userId, body));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /** "No, this isn't me" or "Don't agree" from the signed-in app. Idempotent. */
  @PostMapping("/pairings/abandon")
  public ResponseEntity<ApiResponse<Map<String, Boolean>>> abandon(
      @RequestBody(required = false) AbandonPairingRequest body, HttpServletRequest request) {
    String userId = worker(request);
    if (body == null || !StringUtils.hasText(body.getClaimToken())) {
      throw new ValidationException("claimToken is required");
    }
    pairingService.abandon(userId, body.getClaimToken());
    return ResponseEntity.ok(ApiResponse.success(Map.of("abandoned", true)));
  }

  // ---- history (R7.9, R8.4) -------------------------------------------------------------------

  /**
   * The History module: the caller's own punches whose day lies in {@code [from, to]}, newest first,
   * at most 31 days; the last 31 including today when the range is left out. The employee is the
   * token's, never a query parameter's.
   */
  @GetMapping("/punches")
  public ResponseEntity<ApiResponse<List<PunchResponse>>> punches(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletRequest request) {
    String userId = worker(request);
    AttendanceAppController.requireDateShape("from", from);
    AttendanceAppController.requireDateShape("to", to);
    return ResponseEntity.ok(ApiResponse.success(selfService.punches(userId,
        from != null ? from.trim() : null, to != null ? to.trim() : null)));
  }

  // ---- profile (R8.1, R8.2) -------------------------------------------------------------------

  @GetMapping("/profile")
  public ResponseEntity<ApiResponse<WorkerProfileResponse>> profile(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.profile(worker(request))));
  }

  /** "Edit details": name and phone on the account and the linked employee (R8.2). */
  @PutMapping("/profile")
  public ResponseEntity<ApiResponse<WorkerProfileResponse>> updateProfile(
      @RequestBody(required = false) UpdateWorkerProfileRequest body,
      HttpServletRequest request) {
    String userId = worker(request);
    if (body == null) {
      throw new ValidationException("A request body is required");
    }
    return ResponseEntity.ok(ApiResponse.success(
        selfService.updateProfile(userId, body.getName(), body.getPhone())));
  }

  // ---- Leave this shop (R5.3) -----------------------------------------------------------------

  /** Exits the caller's own employee today, as {@code self}; answers the new state. */
  @PostMapping("/leave")
  public ResponseEntity<ApiResponse<WorkerStateResponse>> leave(HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.leave(worker(request))));
  }

  // ---- notifications (R10.1) ------------------------------------------------------------------

  /** The account's rows across shops, unread first then newest, at most 20. */
  @GetMapping("/notifications")
  public ResponseEntity<ApiResponse<HrAccountNotificationsResponse>> notifications(
      HttpServletRequest request) {
    return ResponseEntity.ok(ApiResponse.success(selfService.notifications(worker(request))));
  }

  /** Marks one of the caller's own rows read. */
  @PostMapping("/notifications/{id}/read")
  public ResponseEntity<ApiResponse<Map<String, Boolean>>> markRead(
      @PathVariable String id, HttpServletRequest request) {
    String userId = worker(request);
    validator.requireObjectId(id, "id");
    selfService.markRead(userId, id.trim());
    return ResponseEntity.ok(ApiResponse.success(Map.of("read", true)));
  }

  // ---- helpers --------------------------------------------------------------------------------

  /** The caller, from the attribute {@code AuthenticationInterceptor} set; never from the request. */
  private static String user(HttpServletRequest request) {
    return HrRequestUtils.requireUserId(request);
  }

  /** The caller, after the "who may do what" rule for staff accounts without an employee. */
  private String worker(HttpServletRequest request) {
    String userId = user(request);
    selfService.requireWorkerAccess(userId);
    return userId;
  }
}
