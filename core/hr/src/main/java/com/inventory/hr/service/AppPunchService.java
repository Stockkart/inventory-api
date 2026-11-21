package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.PairingStatus;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.EmployeeRepository;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.request.AppPunchRequest;
import com.inventory.hr.rest.dto.response.AppPunchResponse;
import com.inventory.hr.rest.dto.response.AppPunchResponse.Outcome;
import com.inventory.hr.rest.dto.response.AppStatusResponse;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.utils.AppSignatures;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.utils.constants.HrMetricsConstants.AppPunchResult;
import com.inventory.hr.validation.AppPunchValidator;
import com.inventory.metrics.MetricsWrapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Punches from the Attendance app (HR-module R4–R6, design "Server-side check order for an app
 * punch"). The caller has already proven the pairing through the signed request envelope ({@link
 * AttendanceAppAuthInterceptor}); the shop and employee used here are the pairing's, never the
 * body's.
 *
 * <p>Order of checks for {@link #punch}, stopping at the first that fails: rate limit → field
 * shapes → punch signature → replay (same punch id) → offline windows → the My HR refusals in the
 * My HR order (switch on → pairing active → employed that day → not on full-day leave → salary
 * month open) → location rule. Every refusal stores nothing; its only side effect is one result
 * counter. The time of an online punch is the server's; of an offline punch the phone's (R4.4,
 * R6.9). The direction is decided from the day's stored punches (R4.9).
 *
 * <p>Idempotence (R5.10–R5.11). The dedupe key is {@code B:<employeeId>:<punchId>}. A resend with
 * the same signed content is answered with the stored punch's result and stores nothing; a resend
 * with different content is a tampering attempt and refused like a bad signature.
 */
@Slf4j
@Service
public class AppPunchService {

  /** An offline punch's phone time may be at most this far ahead of the server (R6.12). */
  static final Duration CLOCK_AHEAD_MAX = Duration.ofMinutes(5);

  /** An offline punch must reach the server within this of its phone time (R6.11). */
  static final Duration OFFLINE_MAX_AGE = Duration.ofHours(24);

  /** An accepted offline punch older than this counts towards the backlog panel (R10.7b). */
  static final Duration BACKLOG_AFTER = Duration.ofHours(1);

  /** The history screen covers at most 31 calendar days including today (R4.10). */
  static final int HISTORY_MAX_DAYS = 31;

  static final String DEDUPE_PREFIX = "B:";

  @Autowired private HrSettingsService settingsService;

  @Autowired private EmployeeRepository employeeRepository;

  @Autowired private SelfCheckInService selfCheckIn;

  @Autowired private PunchDayService punchDays;

  @Autowired private PunchRepository punchRepository;

  @Autowired private DevicePairingService pairingService;

  @Autowired private AppRateLimiter rateLimiter;

  @Autowired private AppPunchValidator validator;

  @Autowired private ConsentVersions consentVersions;

  @Autowired private MetricsWrapper metrics;

  /** Advisory health window; optional so a wiring without it (tests) still answers punches. */
  @Autowired(required = false)
  private AppPunchHealthService health;

  private Clock clock = Clock.systemUTC();

  /** Tests pin the clock. */
  void setClock(Clock clock) {
    this.clock = clock;
  }

  // ---- status --------------------------------------------------------------------------------

  /** {@code GET /attendance-app/me}: what the home screen shows (R4.1); never throws for a refusal. */
  public AppStatusResponse status(DevicePairing pairing) {
    Objects.requireNonNull(pairing, "pairing");
    Instant now = now();
    LocalDate today = today(now);
    String d = HrDateUtils.format(today);
    HrSettings settings = settingsService.effectiveSettings(pairing.getShopId());
    HrSettings.AttendanceCapture capture = capture(settings);
    Employee employee = requireEmployee(pairing);
    BaseException refusal = selfCheckIn.refusal(PunchSource.BIOMETRIC_APP, pairing.getShopId(),
        null, isActive(pairing), settings, capture, employee, today, d);
    PunchDayDeriver.Derived derived = PunchDayService.derive(
        punchDays.punchesOf(pairing.getShopId(), employee.getId(), d), employee, d, settings);

    AppStatusResponse r = new AppStatusResponse();
    r.setEmployeeName(employee.getName());
    r.setShopName(pairingService.shopName(pairing.getShopId()));
    r.setToday(d);
    r.setBiometricApp(Boolean.TRUE.equals(capture.getBiometricApp()));
    r.setCanPunch(refusal == null);
    r.setReason(refusal != null ? refusal.getMessage() : null);
    r.setNextDirection(nextDirection(derived));
    r.setTodayIn(derived.inTime());
    r.setTodayOut(derived.outTime());
    r.setLocation(DevicePairingService.locationSettings(capture));
    r.setConsent(new AppStatusResponse.ConsentState(consentVersions.latest().version(),
        consentVersions.reconsentRequired(pairing.getConsentVersion()),
        pairing.getConsentVersion()));
    return r;
  }

  // ---- punch ---------------------------------------------------------------------------------

  /**
   * {@code POST /attendance-app/punches}: one punch, a replay answer, or a refusal (nothing
   * stored). Exactly one {@code inventory_hr_app_punches_total{result}} increment per call (R10.1,
   * R10.2).
   */
  public AppPunchResponse punch(DevicePairing pairing, AppPunchRequest request) {
    Objects.requireNonNull(pairing, "pairing");
    String shopId = pairing.getShopId();
    try {
      AppPunchResponse response = doPunch(pairing, request);
      count(shopId, resultOf(response));
      return response;
    } catch (BaseException e) {
      count(shopId, result(e));
      throw e;
    } catch (RuntimeException e) {
      count(shopId, AppPunchResult.ERROR);
      throw e;
    }
  }

  private AppPunchResponse doPunch(DevicePairing pairing, AppPunchRequest request) {
    // 1. rate limit (R9.9): the 21st punch in a minute is refused before any work
    if (!rateLimiter.tryAcquirePunch(pairing.getId())) {
      throw new BaseException(ErrorCode.HR_TOO_MANY_ATTEMPTS,
          ErrorCode.HR_TOO_MANY_ATTEMPTS.getMessage());
    }
    // 2. shapes (400)
    AppSignatures.PunchFields fields = validator.validate(request);
    // 3. the request must be about this pairing, and the punch key must have signed it (R5.7)
    if (!pairing.getId().equals(fields.pairingId())
        || !pairing.getShopId().equals(fields.shopId())
        || !pairing.getEmployeeId().equals(fields.employeeId())) {
      throw notVerified();
    }
    byte[] payload = AppSignatures.punchPayload(fields);
    if (!AppSignatures.verify(pairing.getPunchPublicKey(), payload, request.getSignature())) {
      throw notVerified();
    }
    String payloadSha256 = AppSignatures.sha256Hex(payload);
    String shopId = pairing.getShopId();
    String employeeId = pairing.getEmployeeId();
    String dedupeKey = dedupeKey(employeeId, fields.punchId());

    // 4. replay (R5.10, R5.11): same content → the stored result; different → tampering
    Optional<Punch> stored = punchRepository.findByShopIdAndDedupeKey(shopId, dedupeKey);
    if (stored.isPresent()) {
      return replay(stored.get(), payloadSha256, pairing);
    }

    // server time, whole seconds, taken once so the checks and the punch are for the same moment
    Instant now = Instant.ofEpochSecond(now().getEpochSecond());
    // 5. offline windows (R6.11, R6.12); values exactly on a boundary are accepted
    if (fields.offline()) {
      if (fields.phoneTime().isAfter(now.plus(CLOCK_AHEAD_MAX))) {
        throw new BaseException(ErrorCode.HR_APP_CLOCK_WRONG,
            ErrorCode.HR_APP_CLOCK_WRONG.getMessage());
      }
      if (fields.phoneTime().isBefore(now.minus(OFFLINE_MAX_AGE))) {
        throw new BaseException(ErrorCode.HR_APP_TOO_LATE,
            ErrorCode.HR_APP_TOO_LATE.getMessage());
      }
    }
    // 6. the punch's time and day: the server's for an online punch, the phone's offline (R4.4,
    // R6.9); the refusals are evaluated for that day (R6.10)
    Instant at = fields.offline() ? fields.phoneTime() : now;
    LocalDate day = today(at);
    String d = HrDateUtils.format(day);

    // 7. the My HR refusals in the My HR order (R4.3); the switch is read now, per punch (R1.3)
    HrSettings settings = settingsService.effectiveSettings(shopId);
    HrSettings.AttendanceCapture capture = capture(settings);
    Employee employee = requireEmployee(pairing);
    BaseException refusal = selfCheckIn.refusal(PunchSource.BIOMETRIC_APP, shopId, null,
        isActive(pairing), settings, capture, employee, day, d);
    if (refusal != null) {
      throw refusal;
    }
    // 8. location rule on the phone-computed distance; a mock reading replaces it (R4.5, R4.6)
    SelfCheckInService.Location location = SelfCheckInService.locationFromDistance(capture,
        fields.distanceMetres(), fields.accuracyMetres(), fields.mock());

    // 9. store
    List<Punch> before = punchDays.punchesOf(shopId, employeeId, d);
    PunchDayDeriver.Derived existing = PunchDayService.derive(before, employee, d, settings);
    List<PunchFlag> flags = new ArrayList<>(location.flags());
    if (fields.offline()) {
      flags.add(PunchFlag.RECORDED_OFFLINE);
    }
    Punch punch = new Punch();
    punch.setShopId(shopId);
    punch.setEmployeeId(employeeId);
    punch.setAt(at);
    punch.setLocalDate(d);
    punch.setSource(PunchSource.BIOMETRIC_APP);
    // the My HR rule (R4.9): IN when the day has no counted punch yet, else OUT
    punch.setDirection(nextDirection(existing));
    punch.setDistanceMetres(location.distanceMetres());
    punch.setAccuracyMetres(location.accuracyMetres());
    punch.setFlags(flags);
    punch.setDedupeKey(dedupeKey);
    punch.setPairingId(pairing.getId());
    punch.setDeviceModel(pairing.getPhoneModel());
    punch.setPhoneTime(fields.phoneTime());
    punch.setPayloadSha256(payloadSha256);
    punch.setCreatedAt(now);
    try {
      punchRepository.insert(punch);
    } catch (DuplicateKeyException dup) {
      // the same punch id landed between the replay read and this insert (a resend racing the
      // first send): answer as a replay of whatever is stored
      Punch raced = punchRepository.findByShopIdAndDedupeKey(shopId, dedupeKey)
          .orElseThrow(() -> notVerified());
      return replay(raced, payloadSha256, pairing);
    }

    // 10. read again after the insert, so a punch stored meanwhile is included and the derived
    // day is never worked out from a partial view
    List<Punch> after = punchDays.punchesOf(shopId, employeeId, d);
    PunchDayDeriver.Derived derived = PunchDayService.derive(after, employee, d, settings);
    punchDays.writeDerived(shopId, employee, d, settings, derived);
    pairingService.recordLastPunch(pairing.getId(), at);
    if (fields.offline() && Duration.between(fields.phoneTime(), now).compareTo(BACKLOG_AFTER) > 0) {
      metrics.increment(HrMetricsConstants.APP_OFFLINE_BACKLOG_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, HrMetricsConstants.TAG_SHOP, shopId);
    }

    boolean duplicate = !isCounted(derived, dedupeKey);
    return response(duplicate ? Outcome.DUPLICATE : Outcome.STORED, punch, duplicate, derived);
  }

  /** The stored result for a resend (R5.10), or a refusal when the content differs (R5.11). */
  private AppPunchResponse replay(Punch stored, String payloadSha256, DevicePairing pairing) {
    if (stored.getPayloadSha256() == null || !stored.getPayloadSha256().equals(payloadSha256)) {
      throw notVerified();
    }
    Employee employee = requireEmployee(pairing);
    HrSettings settings = settingsService.effectiveSettings(pairing.getShopId());
    List<Punch> punches = punchDays.punchesOf(pairing.getShopId(), pairing.getEmployeeId(),
        stored.getLocalDate());
    PunchDayDeriver.Derived derived = PunchDayService.derive(punches, employee,
        stored.getLocalDate(), settings);
    boolean duplicate = !isCounted(derived, stored.getDedupeKey());
    return response(Outcome.REPLAYED, stored, duplicate, derived);
  }

  private static AppPunchResponse response(Outcome outcome, Punch punch, boolean duplicate,
      PunchDayDeriver.Derived derived) {
    return new AppPunchResponse(outcome, punch.getDirection(), punch.getAt(),
        PunchDayDeriver.hhmm(punch.getAt()),
        punch.getFlags() != null ? List.copyOf(punch.getFlags()) : List.of(), duplicate,
        derived.inTime(), derived.outTime(), nextDirection(derived));
  }

  // ---- history -------------------------------------------------------------------------------

  /**
   * {@code GET /attendance-app/punches?from=&to=}: the caller's own stored punches whose day lies
   * in {@code [from, to]}, newest first, each marked counted or not by the day's 5-minute rule
   * (R4.10, R8.9). Defaults to the last 31 calendar days including today; a wider range is
   * refused.
   */
  public List<PunchResponse> myPunches(DevicePairing pairing, String from, String to) {
    Objects.requireNonNull(pairing, "pairing");
    LocalDate today = today(now());
    LocalDate toDate = StringUtils.hasText(to) ? requireDate(to, "to") : today;
    LocalDate fromDate = StringUtils.hasText(from) ? requireDate(from, "from")
        : toDate.minusDays(HISTORY_MAX_DAYS - 1);
    if (fromDate.isAfter(toDate)) {
      throw new ValidationException("from must not be after to");
    }
    if (fromDate.plusDays(HISTORY_MAX_DAYS - 1).isBefore(toDate)) {
      throw new ValidationException("The range may cover at most " + HISTORY_MAX_DAYS + " days");
    }
    String shopId = pairing.getShopId();
    String employeeId = pairing.getEmployeeId();
    Employee employee = requireEmployee(pairing);
    HrSettings settings = settingsService.effectiveSettings(shopId);
    List<Punch> punches = punchRepository.findRangeNewestFirst(shopId, employeeId,
        HrDateUtils.format(fromDate), HrDateUtils.format(toDate));

    // which punches count is decided per day; the work end plays no part in it
    Map<String, List<Punch>> byDay = new LinkedHashMap<>();
    for (Punch p : punches) {
      if (p.getLocalDate() != null) {
        byDay.computeIfAbsent(p.getLocalDate(), x -> new ArrayList<>()).add(p);
      }
    }
    Set<String> countedIds = new HashSet<>();
    for (Map.Entry<String, List<Punch>> e : byDay.entrySet()) {
      LocalDate day = HrDateUtils.parseDate(e.getKey());
      for (Punch c : PunchDayDeriver.derive(e.getValue(), day, day, LocalTime.MIN, null)
          .counted()) {
        countedIds.add(identity(c));
      }
    }
    List<PunchResponse> rows = new ArrayList<>(punches.size());
    for (Punch p : punches) {
      if (!employeeId.equals(p.getEmployeeId())) {
        // defence in depth (R8.9): the query is scoped, but never return another employee's punch
        continue;
      }
      rows.add(new PunchResponse(p.getId(), p.getEmployeeId(), p.getAt(),
          p.getAt() != null ? PunchDayDeriver.hhmm(p.getAt()) : null, p.getLocalDate(),
          p.getSource(), p.getDirection(), p.getDistanceMetres(), p.getAccuracyMetres(),
          p.getFlags() != null ? List.copyOf(p.getFlags()) : List.of(),
          countedIds.contains(identity(p)), p.getDeviceModel()));
    }
    return rows;
  }

  // ---- helpers -------------------------------------------------------------------------------

  static String dedupeKey(String employeeId, String punchId) {
    return DEDUPE_PREFIX + employeeId + ":" + punchId;
  }

  private static PunchDirection nextDirection(PunchDayDeriver.Derived derived) {
    return derived.isEmpty() ? PunchDirection.IN : PunchDirection.OUT;
  }

  /** Whether the punch with that dedupe key is one of the day's counted punches. */
  private static boolean isCounted(PunchDayDeriver.Derived derived, String dedupeKey) {
    return derived.counted().stream().anyMatch(p -> dedupeKey.equals(p.getDedupeKey()));
  }

  /** A stable handle for a punch read twice: its id, else its dedupe key. */
  private static String identity(Punch p) {
    return p.getId() != null ? "id:" + p.getId() : "key:" + p.getDedupeKey();
  }

  private static boolean isActive(DevicePairing pairing) {
    return pairing.getStatus() == PairingStatus.ACTIVE;
  }

  private static HrSettings.AttendanceCapture capture(HrSettings settings) {
    return settings != null && settings.getAttendanceCapture() != null
        ? settings.getAttendanceCapture() : HrSettingsService.captureWithDefaults(null);
  }

  private Employee requireEmployee(DevicePairing pairing) {
    return employeeRepository.findByShopIdAndId(pairing.getShopId(), pairing.getEmployeeId())
        .orElseThrow(() -> new ResourceNotFoundException("Employee", "id",
            pairing.getEmployeeId()));
  }

  private static BaseException notVerified() {
    return new BaseException(ErrorCode.HR_APP_NOT_VERIFIED,
        ErrorCode.HR_APP_NOT_VERIFIED.getMessage());
  }

  private static LocalDate requireDate(String value, String field) {
    LocalDate date = HrDateUtils.parseDate(value);
    if (date == null) {
      throw new ValidationException(field + " must be a date like 2025-04-07");
    }
    return date;
  }

  private Instant now() {
    return Instant.now(clock);
  }

  private static LocalDate today(Instant at) {
    return at.atZone(HrDateUtils.ZONE).toLocalDate();
  }

  // ---- metrics -------------------------------------------------------------------------------

  /** The one result label of an answered punch (R10.2). */
  static String resultOf(AppPunchResponse response) {
    if (response.getOutcome() == Outcome.REPLAYED) {
      return AppPunchResult.REPLAYED;
    }
    if (response.isDuplicate()) {
      return AppPunchResult.DUPLICATE;
    }
    List<PunchFlag> flags = response.getFlags() != null ? response.getFlags() : List.of();
    if (flags.contains(PunchFlag.RECORDED_OFFLINE)) {
      return AppPunchResult.OFFLINE_ACCEPTED;
    }
    return flags.isEmpty() ? AppPunchResult.ACCEPTED : AppPunchResult.FLAGGED;
  }

  /** Bounded result label for a refusal; the app-only codes first, then the My HR table. */
  static String result(BaseException e) {
    if (e instanceof ValidationException) {
      return AppPunchResult.INVALID;
    }
    ErrorCode code = e.getErrorCode();
    if (code == null) {
      return AppPunchResult.ERROR;
    }
    return switch (code) {
      case HR_TOO_MANY_ATTEMPTS -> AppPunchResult.RATE_LIMITED;
      case HR_APP_NOT_VERIFIED -> AppPunchResult.SIGNATURE_FAILED;
      case HR_APP_TOO_LATE -> AppPunchResult.OFFLINE_TOO_LATE;
      case HR_APP_CLOCK_WRONG -> AppPunchResult.OFFLINE_CLOCK_WRONG;
      default -> SelfCheckInService.result(e);
    };
  }

  /**
   * The one metric increment per attempt (R10.1–R10.2) and the same attempt in the shop's health
   * window (R10.4). The health call is advisory and never changes the punch answer.
   */
  private void count(String shopId, String result) {
    metrics.increment(HrMetricsConstants.APP_PUNCHES_TOTAL, 1,
        "module", HrMetricsConstants.MODULE,
        HrMetricsConstants.TAG_SHOP, shopId,
        HrMetricsConstants.TAG_RESULT, result);
    if (health != null) {
      try {
        health.record(shopId, AppPunchResult.SIGNATURE_FAILED.equals(result));
      } catch (RuntimeException e) {
        log.warn("Attendance app health record failed: {}", e.toString());
      }
    }
  }
}
