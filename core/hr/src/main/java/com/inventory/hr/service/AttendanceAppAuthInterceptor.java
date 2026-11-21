package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.AppNonce;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.repository.AppNonceRepository;
import com.inventory.hr.domain.repository.DevicePairingRepository;
import com.inventory.hr.service.AttendanceAppBodyCachingFilter.CachedBodyRequest;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.AppSignatures.EnvelopeFields;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPunchResult;
import com.inventory.metrics.MetricsWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Authenticates Attendance app requests by their signed request envelope (design "Canonical
 * payloads", R9.8). Registered in {@code CorsConfig} on exactly {@value #PATH_PATTERN}; those paths
 * are excluded from the Bearer, RBAC and plan-expiry interceptors, except {@value #HEALTH_PATH},
 * which stays on the Bearer chain.
 *
 * <p>Every request except the unsigned pairing calls ({@link #UNSIGNED_PATHS}) must carry:
 *
 * <ul>
 *   <li>{@code X-Pairing-Id}: the pairing id; the pairing must exist and be {@code ACTIVE},
 *   <li>{@code X-Request-Timestamp}: epoch seconds within ±10 minutes of server time,
 *   <li>{@code X-Request-Nonce}: a UUID never seen before for that pairing (stored in {@code
 *       hr_app_nonces}, unique {@code (pairingId, nonce)}, TTL 15 minutes),
 *   <li>{@code X-Request-Signature}: Base64 DER ECDSA over {@link AppSignatures#requestEnvelope}
 *       built from the method, path, raw query string, timestamp, nonce and the SHA-256 of the
 *       exact body bytes (buffered by {@link AttendanceAppBodyCachingFilter}), made with the
 *       pairing's device key.
 * </ul>
 *
 * <p>Outcomes: an unknown or {@code UNPAIRED} pairing answers {@code HR_APP_NOT_PAIRED} (403, the
 * app wipes itself, R3.2–R3.3); any other failure answers {@code HR_APP_NOT_VERIFIED} (422, R5.8).
 * Both increment {@code inventory_hr_app_punches_total{result=signature_failed}} once, so the
 * signature-failure rate (R10.4) sees tampered or broken phones whichever path they call first.
 * The rate limiter is not touched here: it lives in the punch service, which an unpaired phone
 * never reaches, so a wiped phone is not punished (design "Server-side rules").
 *
 * <p>On success the request carries attributes {@code pairingId}, {@code shopId}, {@code
 * employeeId} and an {@link AppRequestContext}; controllers read identity from there and never from
 * the body. Every response, success or refusal, carries {@code X-Server-Time} (epoch seconds) so
 * the app can correct its envelope timestamps (never its punch times).
 */
@Slf4j
@Component
public class AttendanceAppAuthInterceptor implements HandlerInterceptor {

  /** Path prefix of every Attendance app endpoint. */
  public static final String PATH_PREFIX = "/api/v1/hr/attendance-app";

  /** The pattern this interceptor is registered on. */
  public static final String PATH_PATTERN = PATH_PREFIX + "/**";

  /** Manager-facing health read: Bearer chain, {@code VIEW_ATTENDANCE}; not a device path. */
  public static final String HEALTH_PATH = PATH_PREFIX + "/health";

  /**
   * The paths the phone calls. Excluded from the Bearer, RBAC and plan-expiry interceptors in
   * {@code CorsConfig}. Listed one by one, rather than {@code /**}, so {@link #HEALTH_PATH} and
   * any future manager path under the prefix stay on the Bearer chain.
   */
  public static final List<String> DEVICE_PATH_PATTERNS =
      List.of(
          PATH_PREFIX + "/pairings/**",
          PATH_PREFIX + "/me",
          PATH_PREFIX + "/punches",
          PATH_PREFIX + "/consent",
          PATH_PREFIX + "/consent/**");

  /**
   * Device paths that carry no envelope: the phone has no keys yet when it claims, completes or
   * abandons a code, and the latest consent text holds no shop data. Matched on the raw request
   * URI by exact equality, so an encoded or {@code ;param} variant is verified, never skipped.
   */
  public static final Set<String> UNSIGNED_PATHS =
      Set.of(
          PATH_PREFIX + "/pairings/claim",
          PATH_PREFIX + "/pairings/complete",
          PATH_PREFIX + "/pairings/abandon",
          PATH_PREFIX + "/consent/latest");

  public static final String HEADER_PAIRING_ID = "X-Pairing-Id";
  public static final String HEADER_TIMESTAMP = "X-Request-Timestamp";
  public static final String HEADER_NONCE = "X-Request-Nonce";
  public static final String HEADER_SIGNATURE = "X-Request-Signature";
  public static final String HEADER_SERVER_TIME = "X-Server-Time";

  /** Same attribute names the Bearer chain uses, so {@code HrRequestUtils.requireShopId} works. */
  public static final String ATTR_PAIRING_ID = "pairingId";
  public static final String ATTR_SHOP_ID = "shopId";
  public static final String ATTR_EMPLOYEE_ID = "employeeId";

  /** How far the phone's envelope timestamp may be from server time, either way. */
  public static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

  private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{24}");

  @Autowired private DevicePairingRepository pairingRepository;

  @Autowired private AppNonceRepository nonceRepository;

  @Autowired private MetricsWrapper metrics;

  /** Advisory health window; optional so a wiring without it (tests) still answers requests. */
  @Autowired(required = false)
  private AppPunchHealthService health;

  private Clock clock = Clock.systemUTC();

  /** Test hook. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Instant now = Instant.now(clock);
    response.setHeader(HEADER_SERVER_TIME, Long.toString(now.getEpochSecond()));

    if ("OPTIONS".equals(request.getMethod())) {
      return true;
    }
    String path = request.getRequestURI();
    if (path != null && UNSIGNED_PATHS.contains(path)) {
      return true;
    }

    DevicePairing pairing = loadPairing(request.getHeader(HEADER_PAIRING_ID));
    if (pairing == null || pairing.getStatus() != PairingStatus.ACTIVE) {
      countSignatureFailed(pairing == null ? null : pairing.getShopId());
      log.info("Attendance app request refused: pairing unknown or not active");
      throw new BaseException(
          ErrorCode.HR_APP_NOT_PAIRED, ErrorCode.HR_APP_NOT_PAIRED.getMessage());
    }

    String failure = verifyEnvelope(request, pairing, path, now);
    if (failure != null) {
      countSignatureFailed(pairing.getShopId());
      log.warn("Attendance app request not verified (pairing {}): {}", pairing.getId(), failure);
      throw new BaseException(
          ErrorCode.HR_APP_NOT_VERIFIED, ErrorCode.HR_APP_NOT_VERIFIED.getMessage());
    }

    AppRequestContext context = AppRequestContext.of(pairing);
    request.setAttribute(ATTR_PAIRING_ID, context.pairingId());
    request.setAttribute(ATTR_SHOP_ID, context.shopId());
    request.setAttribute(ATTR_EMPLOYEE_ID, context.employeeId());
    request.setAttribute(AppRequestContext.ATTRIBUTE, context);
    return true;
  }

  /** The pairing the header names, whatever its status; null when the header or row is missing. */
  private DevicePairing loadPairing(String header) {
    if (!StringUtils.hasText(header)) {
      return null;
    }
    String pairingId = header.trim();
    if (!OBJECT_ID.matcher(pairingId).matches()) {
      return null;
    }
    return pairingRepository.findById(pairingId).orElse(null);
  }

  /**
   * Checks timestamp, headers, signature and nonce, in that order; the nonce is written only after
   * the signature verified, so unsigned garbage cannot burn nonces. Returns a short reason for the
   * log, or null when the envelope is good. Never throws for bad input.
   */
  private String verifyEnvelope(
      HttpServletRequest request, DevicePairing pairing, String path, Instant now) {
    String timestampHeader = request.getHeader(HEADER_TIMESTAMP);
    String nonceHeader = request.getHeader(HEADER_NONCE);
    String signature = request.getHeader(HEADER_SIGNATURE);
    if (!StringUtils.hasText(timestampHeader)
        || !StringUtils.hasText(nonceHeader)
        || !StringUtils.hasText(signature)
        || path == null) {
      return "envelope header missing";
    }
    long timestamp;
    try {
      timestamp = Long.parseLong(timestampHeader.trim());
    } catch (NumberFormatException e) {
      return "timestamp not a number";
    }
    if (Math.abs(timestamp - now.getEpochSecond()) > MAX_CLOCK_SKEW.toSeconds()) {
      return "timestamp outside the allowed window";
    }
    // As sent: the payload is built from the exact header value, and the canonical builder
    // refuses anything but a lowercase UUID with hyphens.
    String nonce = nonceHeader.trim();

    byte[] body;
    CachedBodyRequest cached = CachedBodyRequest.find(request);
    if (cached != null) {
      if (cached.oversized()) {
        return "body too large";
      }
      body = cached.body();
    } else if (hasNoBody(request)) {
      body = new byte[0];
    } else {
      // Only possible when the filter is not registered: refuse rather than verify a hash of
      // nothing against a body the controller will read.
      log.error("Attendance app body was not buffered; is AttendanceAppBodyCachingFilter active?");
      return "body not buffered";
    }

    byte[] payload;
    try {
      payload =
          AppSignatures.requestEnvelope(
              new EnvelopeFields(
                  pairing.getId(),
                  request.getMethod(),
                  path,
                  request.getQueryString(),
                  timestamp,
                  nonce,
                  body));
    } catch (IllegalArgumentException e) {
      return "envelope cannot be built: " + e.getMessage();
    }
    if (!AppSignatures.verify(pairing.getDevicePublicKey(), payload, signature)) {
      return "signature does not verify";
    }

    try {
      nonceRepository.save(new AppNonce(null, pairing.getId(), nonce, now));
    } catch (DuplicateKeyException e) {
      return "nonce already used";
    }
    return null;
  }

  private static boolean hasNoBody(HttpServletRequest request) {
    return "GET".equals(request.getMethod()) && request.getContentLengthLong() <= 0;
  }

  /**
   * One {@code signature_failed} result on the punches counter and one failed attempt in the
   * shop's health window (R10.4). The health call is advisory: a failure there is swallowed so the
   * refusal is still answered.
   */
  private void countSignatureFailed(String shopId) {
    metrics.increment(
        HrMetricsConstants.APP_PUNCHES_TOTAL,
        1,
        "module",
        HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_SHOP,
        shopId != null ? shopId : "unknown",
        HrMetricsConstants.TAG_RESULT,
        AppPunchResult.SIGNATURE_FAILED);
    if (health != null) {
      try {
        health.record(shopId, true);
      } catch (RuntimeException e) {
        log.warn("Attendance app health record failed: {}", e.toString());
      }
    }
  }
}
