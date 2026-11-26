package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.request.ConsentRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppStatusResponse;
import com.inventory.hr.rest.dto.response.ConsentResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.UnpairResponse;
import com.inventory.hr.service.AppPunchService;
import com.inventory.hr.service.AppRequestContext;
import com.inventory.hr.service.ConsentVersions;
import com.inventory.hr.service.DevicePairingService;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Attendance app's own endpoints, {@code /api/v1/hr/attendance-app/**} (design "App
 * endpoints"). These are the device paths: outside the Bearer chain ({@code CorsConfig} excludes
 * them from {@code AuthenticationInterceptor}, {@code RbacModuleInterceptor} and {@code
 * PlanExpiryInterceptor}, DD12) and guarded by {@code AttendanceAppAuthInterceptor} instead.
 *
 * <p>Every call but one is <b>signed</b>: {@code GET /me}, {@code POST /punches}, {@code GET
 * /punches}, {@code POST /consent}, {@code POST /pairings/me/unpair}. For those the interceptor
 * has verified the device-key envelope and stored an {@link AppRequestContext}; handlers read
 * identity from {@link AppRequestContext#require} only, never from the body or the query string. A
 * handler reached without the context (the interceptor did not run) answers {@code
 * HR_APP_NOT_PAIRED}. The one unsigned call is {@code GET /consent/latest}: it carries no shop
 * data, and the interceptor allow-lists exactly that path.
 *
 * <p>There is no pairing handshake here (worker-app D11, DD4): a pairing code is redeemed only on
 * {@code /api/v1/hr/worker/pairings/claim|complete|abandon} with the Bearer token, by a signed-in
 * worker for their own employee record ({@code WorkerController}). The request-shape checks those
 * handlers share with the HR-module bodies live at the bottom of this class.
 *
 * <p>{@code GET /attendance-app/health} is a manager call on the Bearer chain and lives in {@link
 * AttendanceAppHealthController}.
 */
@RestController
@RequestMapping("/api/v1/hr/attendance-app")
@Latency(module = "hr")
@RecordRequestRate(module = "hr")
@RecordStatusCodes(module = "hr")
public class AttendanceAppController {

  /** {@code installationId} is a UUID the app made (R2.11); either case, with hyphens. */
  private static final Pattern UUID_SHAPE = Pattern.compile(
      "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  static final int MAX_PHONE_MODEL_LENGTH = 80;
  static final int MAX_VERSION_LENGTH = 40;

  /** {@code from} / {@code to} of the history read are {@code yyyy-MM-dd}; the service checks the range. */
  private static final Pattern DATE_SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  @Autowired private DevicePairingService pairingService;

  @Autowired private AppPunchService punchService;

  @Autowired private ConsentVersions consentVersions;

  // ---- the one unsigned call (allow-listed in AttendanceAppAuthInterceptor) -------------------

  /**
   * The newest consent text (R9.12), for the one-page privacy note and for an app that wants the
   * words before it has a pairing. Unsigned: no shop or employee data in the answer.
   */
  @GetMapping("/consent/latest")
  public ResponseEntity<ApiResponse<ConsentVersions.ConsentVersion>> latestConsent() {
    return ResponseEntity.ok(ApiResponse.success(consentVersions.latest()));
  }

  // ---- signed calls (identity from AppRequestContext, set by AttendanceAppAuthInterceptor) ------

  /**
   * The home screen (R4.1): names, today, the button label, today's times, the shop's Location
   * check settings and the consent state the app gates punching on (R9.13).
   */
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<AppStatusResponse>> me(HttpServletRequest request) {
    AppRequestContext context = AppRequestContext.require(request);
    return ResponseEntity.ok(ApiResponse.success(punchService.status(context.pairing())));
  }

  /**
   * One punch (R4.2c–R4.9, R6.8–R6.12). The pairing, shop and employee the server trusts are the
   * envelope's; the same ids in the body are part of the signed payload and must match it (R5.7).
   */
  @PostMapping("/punches")
  public ResponseEntity<ApiResponse<AppPunchResponse>> punch(
      @RequestBody(required = false) AppPunchRequest body, HttpServletRequest request) {
    AppRequestContext context = AppRequestContext.require(request);
    if (body == null) {
      throw new ValidationException("A request body is required");
    }
    return ResponseEntity.ok(ApiResponse.success(punchService.punch(context.pairing(), body)));
  }

  /**
   * The history screen (R4.10, R8.9): the caller's own punches whose day lies in {@code [from,
   * to]}, newest first; at most 31 days, the last 31 including today when the range is left out.
   */
  @GetMapping("/punches")
  public ResponseEntity<ApiResponse<List<PunchResponse>>> myPunches(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      HttpServletRequest request) {
    AppRequestContext context = AppRequestContext.require(request);
    requireDateShape("from", from);
    requireDateShape("to", to);
    return ResponseEntity.ok(
        ApiResponse.success(punchService.myPunches(context.pairing(), from, to)));
  }

  /** "I agree" on a newer consent version (R9.13). An unknown version is a 400. */
  @PostMapping("/consent")
  public ResponseEntity<ApiResponse<ConsentResponse>> consent(
      @RequestBody(required = false) ConsentRequest body, HttpServletRequest request) {
    AppRequestContext context = AppRequestContext.require(request);
    if (body == null || body.getVersion() == null) {
      throw new ValidationException("version is required");
    }
    return ResponseEntity.ok(ApiResponse.success(
        pairingService.recordConsent(context.pairingId(), body.getVersion())));
  }

  /**
   * "Remove this phone" (R3.7, R3.9): the pairing is unpaired within the request, reason {@code
   * WORKER}, actor {@code self}, one audit row. Idempotent: a pairing already unpaired by another
   * route still answers {@code unpaired: true}, and the app wipes itself either way.
   */
  @PostMapping("/pairings/me/unpair")
  public ResponseEntity<ApiResponse<UnpairResponse>> unpairMe(HttpServletRequest request) {
    AppRequestContext context = AppRequestContext.require(request);
    pairingService.unpair(context.pairingId(), UnpairReason.WORKER, DevicePairingService.SELF);
    return ResponseEntity.ok(ApiResponse.success(new UnpairResponse(true)));
  }

  // ---- shape checks ----------------------------------------------------------------------------
  // Field shapes only (400); every business refusal is the service's and keeps its own code so the
  // app can show the message word for word. Business rules on the code itself (alphabet, length)
  // are the service's too, because a malformed code is "code wrong" to the worker (R2.14). The
  // handshake bodies are checked here, next to the HR-module DTOs they belong to, and used by
  // {@code WorkerController}, the only handshake (DD4).

  /** The Bearer handshake's step-one body ({@code WorkerController}). */
  static void validate(ClaimPairingRequest body) {
    if (body == null) {
      throw new ValidationException("A request body is required");
    }
    if (!StringUtils.hasText(body.getCode())) {
      throw new ValidationException("code is required");
    }
    requireInstallationId(body.getInstallationId());
    if (body.getOs() == null) {
      throw new ValidationException("os is required");
    }
    requireLength("phoneModel", body.getPhoneModel(), MAX_PHONE_MODEL_LENGTH);
    requireLength("osVersion", body.getOsVersion(), MAX_VERSION_LENGTH);
    requireLength("appVersion", body.getAppVersion(), MAX_VERSION_LENGTH);
  }

  /** The Bearer handshake's step-two body ({@code WorkerController}). */
  static void validate(CompletePairingRequest body) {
    if (body == null) {
      throw new ValidationException("A request body is required");
    }
    if (!StringUtils.hasText(body.getClaimToken())) {
      throw new ValidationException("claimToken is required");
    }
    requireInstallationId(body.getInstallationId());
    if (!StringUtils.hasText(body.getPunchPublicKey())) {
      throw new ValidationException("punchPublicKey is required");
    }
    if (!StringUtils.hasText(body.getDevicePublicKey())) {
      throw new ValidationException("devicePublicKey is required");
    }
    if (body.getConsentVersion() == null) {
      throw new ValidationException("consentVersion is required");
    }
  }

  private static void requireInstallationId(String installationId) {
    if (!StringUtils.hasText(installationId)) {
      throw new ValidationException("installationId is required");
    }
    if (!UUID_SHAPE.matcher(installationId).matches()) {
      throw new ValidationException("installationId must be a UUID");
    }
  }

  private static void requireLength(String field, String value, int max) {
    if (value != null && value.length() > max) {
      throw new ValidationException(field + " must be at most " + max + " characters");
    }
  }

  /** Shared with {@code WorkerController}. */
  static void requireDateShape(String field, String value) {
    if (StringUtils.hasText(value) && !DATE_SHAPE.matcher(value.trim()).matches()) {
      throw new ValidationException(field + " must be a date like 2025-04-07");
    }
  }
}
