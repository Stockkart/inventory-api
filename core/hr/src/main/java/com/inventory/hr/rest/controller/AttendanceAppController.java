package com.inventory.hr.rest.controller;

import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.enums.UnpairReason;
import com.inventory.hr.rest.dto.request.AbandonPairingRequest;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.request.ClaimPairingRequest;
import com.inventory.hr.rest.dto.request.CompletePairingRequest;
import com.inventory.hr.rest.dto.request.ConsentRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppStatusResponse;
import com.inventory.hr.rest.dto.response.ClaimPairingResponse;
import com.inventory.hr.rest.dto.response.ConsentResponse;
import com.inventory.hr.rest.dto.response.PairingBootstrapResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.UnpairResponse;
import com.inventory.hr.service.AppPunchService;
import com.inventory.hr.service.AppRateLimiter;
import com.inventory.hr.service.AppRequestContext;
import com.inventory.hr.service.ConsentVersions;
import com.inventory.hr.service.DevicePairingService;
import com.inventory.metrics.annotation.Latency;
import com.inventory.metrics.annotation.RecordRequestRate;
import com.inventory.metrics.annotation.RecordStatusCodes;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
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
 * endpoints"). Workers have no StockKart login (D7), so this path is outside the Bearer chain:
 * {@code CorsConfig} excludes it from {@code AuthenticationInterceptor}, {@code
 * RbacModuleInterceptor} and {@code PlanExpiryInterceptor} (DD12) and registers {@code
 * AttendanceAppAuthInterceptor} on it instead.
 *
 * <p>Two kinds of call live here:
 *
 * <ol>
 *   <li><b>Unsigned pairing handshake</b>: {@code claim}, {@code complete}, {@code abandon}. The
 *       phone has no key yet, so the pairing code and the short-lived claim token are the only
 *       credentials; {@link AppRateLimiter} applies the per-installation lockout (R2.5). {@code GET
 *       /consent/latest} is unsigned too: it carries no shop data. The interceptor allow-lists
 *       exactly these paths.
 *   <li><b>Signed calls</b>: {@code GET /me}, {@code POST /punches}, {@code GET /punches}, {@code
 *       POST /consent}, {@code POST /pairings/me/unpair}. For those the interceptor has verified
 *       the device-key envelope and stored an {@link AppRequestContext}; handlers read identity
 *       from {@link AppRequestContext#require} only, never from the body or the query string. A
 *       handler reached without the context (the interceptor did not run) answers {@code
 *       HR_APP_NOT_PAIRED}.
 * </ol>
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

  @Autowired private AppRateLimiter rateLimiter;

  @Autowired private AppPunchService punchService;

  @Autowired private ConsentVersions consentVersions;

  // ---- unsigned pairing handshake (allow-listed in AttendanceAppAuthInterceptor) ----------------

  /**
   * Step one (R2.6, R2.14): the code the worker scanned or typed. Answers the names to confirm,
   * the consent text and a claim token. Refusals are the R2.14 codes; five of them from one
   * installation in 15 minutes lock it for 15 minutes (R2.5, {@code HR_PAIRING_CODE_LOCKED}).
   */
  @PostMapping("/pairings/claim")
  public ResponseEntity<ApiResponse<ClaimPairingResponse>> claim(
      @RequestBody ClaimPairingRequest body) {
    validate(body);
    ClaimPairingResponse response = rateLimiter.guardCodeAttempt(body.getInstallationId(),
        () -> pairingService.claim(body.getCode(), body.getInstallationId(), body.getOs(),
            body.getOsVersion(), body.getPhoneModel(), body.getAppVersion()));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /**
   * Step two, "I agree" (R2.9): registers the phone's public keys and activates the pairing. The
   * answer is everything the app keeps about its pairing (R9.3). An optional {@code
   * attestationChain} (Android key attestation of the punch key, task 16.4) is verified and recorded
   * on the pairing; it never changes the answer in this version.
   */
  @PostMapping("/pairings/complete")
  public ResponseEntity<ApiResponse<PairingBootstrapResponse>> complete(
      @RequestBody CompletePairingRequest body) {
    validate(body);
    PairingBootstrapResponse response = rateLimiter.guardCodeAttempt(body.getInstallationId(),
        () -> pairingService.complete(body.getClaimToken(), body.getInstallationId(),
            body.getPunchPublicKey(), body.getDevicePublicKey(), body.getConsentVersion(),
            body.getAttestationChain()));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  /** "No, this isn't me" or "Don't agree" (R2.6, R2.8): the code can never pair. Idempotent. */
  @PostMapping("/pairings/abandon")
  public ResponseEntity<ApiResponse<Map<String, Boolean>>> abandon(
      @RequestBody AbandonPairingRequest body) {
    if (body == null || !StringUtils.hasText(body.getClaimToken())) {
      throw new ValidationException("claimToken is required");
    }
    pairingService.abandon(body.getClaimToken());
    return ResponseEntity.ok(ApiResponse.success(Map.of("abandoned", true)));
  }

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
  // are the service's too, because a malformed code is "code wrong" to the worker (R2.14).

  private static void validate(ClaimPairingRequest body) {
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

  private static void validate(CompletePairingRequest body) {
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

  private static void requireDateShape(String field, String value) {
    if (StringUtils.hasText(value) && !DATE_SHAPE.matcher(value.trim()).matches()) {
      throw new ValidationException(field + " must be a date like 2025-04-07");
    }
  }
}
