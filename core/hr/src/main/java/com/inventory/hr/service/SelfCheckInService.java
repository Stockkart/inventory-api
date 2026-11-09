package com.inventory.hr.service;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.request.SelfCheckInRequest;
import com.inventory.hr.rest.dto.response.PunchResponse;
import com.inventory.hr.rest.dto.response.SelfCheckInResponse;
import com.inventory.hr.utils.GeoDistance;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Self check-in on My HR (R2, R3). {@code /api/v1/hr/me/**} skips the RBAC module check, so the
 * SELF_CHECK_IN permission is checked here, after the employee is found from the caller's own
 * login (never from the body).
 *
 * <p>Order of checks (design "Self check-in order of checks"): self check-in on → linked →
 * SELF_CHECK_IN → employed today → not on approved full-day leave → salary month open → location
 * rule. Every refusal stores nothing. The time is the server's; the direction is IN when the
 * employee has no counted punch today, else OUT.
 *
 * <p>Privacy (R3.6): the request's coordinates are used only to work out the distance in this
 * method; they are not stored, logged, audited or put into any message. Punches are their own
 * record, so no audit row is written per punch.
 */
@Slf4j
@Service
public class SelfCheckInService {

  /** R3.4: a reported accuracy worse than this (metres) is "location not clear". */
  static final double MAX_ACCURACY_METRES = 200;

  static final String REASON_OFF = ErrorCode.HR_SELF_CHECKIN_OFF.getMessage();
  static final String REASON_NO_PERMISSION =
      "Your role cannot use self check-in. Ask the shop owner to allow it.";
  static final String REASON_NOT_EMPLOYED = "You are not employed on today's date";
  static final String REASON_ON_LEAVE = "You are on approved leave today";
  static final String REASON_LOCKED =
      "Payroll for this month is finalised, so attendance cannot be recorded";

  @Autowired private HrSettingsService settingsService;

  @Autowired private SelfServiceResolver selfServiceResolver;

  @Autowired private HrAccessService hrAccessService;

  @Autowired private AttendanceService attendanceService;

  @Autowired private PeriodLockService periodLockService;

  @Autowired private PunchDayService punchDays;

  @Autowired private PunchRepository punchRepository;

  @Autowired private MetricsWrapper metrics;

  /** {@code GET /me/check-in}: what the button should show; never throws for a refusal. */
  public SelfCheckInResponse status(String shopId, String userId) {
    HrPermissionGuard.requireContext(userId, shopId);
    Check check = check(shopId, userId, Instant.now());
    List<Punch> punches = check.employee() != null
        ? punchDays.punchesOf(shopId, check.employee().getId(), check.date()) : List.of();
    return response(check, punches, check.refusal());
  }

  /** {@code POST /me/check-in}: one punch, or a refusal (nothing stored). */
  public SelfCheckInResponse checkIn(String shopId, String userId, SelfCheckInRequest request) {
    HrPermissionGuard.requireContext(userId, shopId);
    // server time only (R2.4), taken once, so the checks and the punch are for the same day;
    // whole seconds so the dedupe key matches the stored time
    Instant now = Instant.ofEpochSecond(Instant.now().getEpochSecond());
    try {
      Check check = check(shopId, userId, now);
      if (check.refusal() != null) {
        throw check.refusal();
      }
      Location location = location(check.capture(), request);
      return punch(shopId, check, location, now);
    } catch (BaseException e) {
      count(result(e));
      throw e;
    } catch (RuntimeException e) {
      count("error");
      throw e;
    }
  }

  /** {@code GET /me/punches?date=}: the caller's own punches of a day (default today). */
  public List<PunchResponse> myPunches(String shopId, String userId, String date) {
    Employee self = selfServiceResolver.resolve(shopId, userId);
    String d = requireDate(date);
    HrSettings settings = settingsService.effectiveSettings(shopId);
    return PunchDayService.toResponses(punchDays.punchesOf(shopId, self.getId(), d), self, d,
        settings);
  }

  // ---- checks --------------------------------------------------------------------------------

  /**
   * @param employee null when not linked
   * @param refusal first failed check before the location rule; null = allowed
   */
  record Check(HrSettings settings, HrSettings.AttendanceCapture capture, Employee employee,
      LocalDate today, String date, BaseException refusal) {}

  private Check check(String shopId, String userId, Instant now) {
    LocalDate today = now.atZone(HrDateUtils.ZONE).toLocalDate();
    String d = HrDateUtils.format(today);
    HrSettings settings = settingsService.effectiveSettings(shopId);
    HrSettings.AttendanceCapture capture = settings.getAttendanceCapture() != null
        ? settings.getAttendanceCapture() : HrSettingsService.captureWithDefaults(null);
    Employee employee;
    try {
      employee = selfServiceResolver.resolve(shopId, userId);
    } catch (BaseException e) {
      if (e.getErrorCode() != ErrorCode.HR_NOT_LINKED) {
        // a database error or timeout is not a refusal
        throw e;
      }
      if (!Boolean.TRUE.equals(capture.getSelfCheckIn())) {
        return new Check(settings, capture, null, today, d, off());
      }
      return new Check(settings, capture, null, today, d, e);
    }
    BaseException refusal = refusal(shopId, userId, settings, capture, employee, today, d);
    return new Check(settings, capture, employee, today, d, refusal);
  }

  private BaseException refusal(String shopId, String userId, HrSettings settings,
      HrSettings.AttendanceCapture capture, Employee employee, LocalDate today, String d) {
    if (!Boolean.TRUE.equals(capture.getSelfCheckIn())) {
      return off();
    }
    // HR switch off → no permissions at all, so this also covers R2.3 "member's HR switch off"
    Set<HrPermission> permissions = hrAccessService.permissions(userId, shopId);
    if (!permissions.contains(HrPermission.SELF_CHECK_IN)) {
      return new BaseException(ErrorCode.ACCESS_DENIED, REASON_NO_PERMISSION);
    }
    if (!DayStatusResolver.isEmployed(today, employee)) {
      return new BaseException(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED, REASON_NOT_EMPLOYED);
    }
    List<LeaveRequest> leave = attendanceService.approvedLeave(shopId, List.of(employee.getId()),
        d, d, HrQueryRunner.SCREEN_MAX_TIME_MS);
    boolean fullDayLeave = leave.stream().anyMatch(l -> !Boolean.TRUE.equals(l.getHalfDay()));
    // leave covers working days only (R6.10): on a week-off or holiday it does not apply
    if (fullDayLeave && !DayStatusResolver.isWeekOff(today, settings)
        && !attendanceService.holidayDates(shopId, d, d, HrQueryRunner.SCREEN_MAX_TIME_MS)
            .contains(d)) {
      return new BaseException(ErrorCode.HR_DAY_HAS_LEAVE, REASON_ON_LEAVE);
    }
    String period = PayCycle.periodOf(d, PayCycle.startDay(settings));
    if (!periodLockService.lockedPeriods(shopId, List.of(period)).isEmpty()) {
      return new BaseException(ErrorCode.HR_PERIOD_LOCKED, REASON_LOCKED);
    }
    return null;
  }

  private static BaseException off() {
    return new BaseException(ErrorCode.HR_SELF_CHECKIN_OFF, REASON_OFF);
  }

  /** Distance and flags; the coordinates do not leave this method. */
  record Location(Integer distanceMetres, Integer accuracyMetres, List<PunchFlag> flags) {}

  static Location location(HrSettings.AttendanceCapture capture, SelfCheckInRequest request) {
    if (!Boolean.TRUE.equals(capture.getLocationCheck())) {
      // location check off: nothing is read from the request
      return new Location(null, null, List.of());
    }
    if (capture.getShopLat() == null || capture.getShopLng() == null) {
      // the settings validator refuses this; fail closed if an old document has it
      throw new ValidationException("The shop location is not set. Ask the shop owner.");
    }
    boolean flag = capture.getOutsideAction() == OutsideAction.FLAG;
    Double lat = request != null ? request.getLat() : null;
    Double lng = request != null ? request.getLng() : null;
    Double accuracy = request != null ? request.getAccuracy() : null;
    if (lat == null || lng == null) {
      // R3.5: no location is treated like outside the shop
      if (!flag) {
        throw new BaseException(ErrorCode.HR_OUTSIDE_SHOP,
            ErrorCode.HR_OUTSIDE_SHOP.getMessage());
      }
      return new Location(null, null, List.of(PunchFlag.NO_LOCATION));
    }
    // written as !(in range) so NaN fails too; the message never repeats the values
    if (!(lat >= -90 && lat <= 90) || !(lng >= -180 && lng <= 180)) {
      throw new ValidationException("The location sent is not valid");
    }
    if (accuracy != null && !(accuracy >= 0 && accuracy < Double.MAX_VALUE)) {
      throw new ValidationException("The location accuracy sent is not valid");
    }
    List<PunchFlag> flags = new ArrayList<>();
    // a missing accuracy is treated as unclear (fail closed)
    if (accuracy == null || accuracy > MAX_ACCURACY_METRES) {
      if (!flag) {
        throw new BaseException(ErrorCode.HR_LOCATION_UNCLEAR,
            ErrorCode.HR_LOCATION_UNCLEAR.getMessage());
      }
      flags.add(PunchFlag.LOW_ACCURACY);
    }
    double metres = GeoDistance.metres(capture.getShopLat(), capture.getShopLng(), lat, lng);
    int radius = capture.getRadiusMetres() != null ? capture.getRadiusMetres()
        : HrSettingsService.DEFAULT_RADIUS_METRES;
    if (metres > radius) {
      if (!flag) {
        throw new BaseException(ErrorCode.HR_OUTSIDE_SHOP,
            ErrorCode.HR_OUTSIDE_SHOP.getMessage());
      }
      flags.add(PunchFlag.OUTSIDE_SHOP);
    }
    Integer accuracyMetres = accuracy != null
        ? (int) Math.min(Integer.MAX_VALUE, Math.ceil(accuracy)) : null;
    return new Location((int) Math.min(Integer.MAX_VALUE, Math.round(metres)), accuracyMetres,
        List.copyOf(flags));
  }

  // ---- punch ---------------------------------------------------------------------------------

  private SelfCheckInResponse punch(String shopId, Check check, Location location, Instant now) {
    Employee e = check.employee();
    String d = check.date();
    List<Punch> before = punchDays.punchesOf(shopId, e.getId(), d);
    PunchDayDeriver.Derived existing = PunchDayService.derive(before, e, d, check.settings());

    Punch punch = new Punch();
    punch.setShopId(shopId);
    punch.setEmployeeId(e.getId());
    punch.setAt(now);
    // the same instant the checks ran for, so this is the day they were made for
    punch.setLocalDate(d);
    punch.setSource(PunchSource.SELF);
    punch.setDirection(existing.isEmpty() ? PunchDirection.IN : PunchDirection.OUT);
    punch.setDistanceMetres(location.distanceMetres());
    punch.setAccuracyMetres(location.accuracyMetres());
    punch.setFlags(new ArrayList<>(location.flags()));
    punch.setDedupeKey(dedupeKey(e.getId(), now));
    punch.setCreatedAt(now);

    String result;
    try {
      punchRepository.insert(punch);
      result = location.flags().isEmpty() ? "accepted" : "flagged";
    } catch (DuplicateKeyException dup) {
      // a second tap in the same second: the first one is the punch
      result = "duplicate";
    }
    // read again after the insert, so a punch stored meanwhile by another tap is included and
    // the derived day is never worked out from a partial view
    List<Punch> after = punchDays.punchesOf(shopId, e.getId(), d);
    PunchDayDeriver.Derived derived = PunchDayService.derive(after, e, d, check.settings());
    punchDays.writeDerived(shopId, e, d, check.settings(), derived);
    count(result);

    SelfCheckInResponse response = response(check, derived, null);
    response.setFlags(new ArrayList<>(location.flags()));
    response.setDistanceMetres(location.distanceMetres());
    return response;
  }

  static String dedupeKey(String employeeId, Instant at) {
    return "S:" + employeeId + ":" + at.getEpochSecond();
  }

  // ---- response ------------------------------------------------------------------------------

  private static SelfCheckInResponse response(Check check, List<Punch> punches,
      BaseException refusal) {
    PunchDayDeriver.Derived derived = PunchDayService.derive(punches, check.employee(),
        check.date(), check.settings());
    return response(check, derived, refusal);
  }

  private static SelfCheckInResponse response(Check check, PunchDayDeriver.Derived derived,
      BaseException refusal) {
    SelfCheckInResponse r = new SelfCheckInResponse();
    boolean enabled = Boolean.TRUE.equals(check.capture().getSelfCheckIn());
    r.setEnabled(enabled);
    r.setLocationRequired(enabled && Boolean.TRUE.equals(check.capture().getLocationCheck()));
    r.setCanCheckIn(refusal == null);
    r.setReason(refusal != null ? refusal.getMessage() : null);
    r.setTodayIn(derived.inTime());
    r.setTodayOut(derived.outTime());
    r.setNextDirection(derived.isEmpty() ? PunchDirection.IN : PunchDirection.OUT);
    r.setPunchCount(derived.punchCount());
    r.setMissingCheckOut(derived.missingCheckOut());
    return r;
  }

  private static String requireDate(String date) {
    if (!StringUtils.hasText(date)) {
      return HrDateUtils.format(HrDateUtils.today());
    }
    if (!HrDateUtils.isDate(date)) {
      throw new ValidationException("date must be a date like 2025-04-07");
    }
    return date;
  }

  private void count(String result) {
    metrics.increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module",
        HrMetricsConstants.MODULE, "result", result);
  }

  /** Bounded metric tag for a refusal. */
  static String result(BaseException e) {
    if (e instanceof ValidationException) {
      return "invalid";
    }
    ErrorCode code = e.getErrorCode();
    if (code == null) {
      return "error";
    }
    return switch (code) {
      case HR_SELF_CHECKIN_OFF -> "off";
      case HR_NOT_LINKED -> "not_linked";
      case ACCESS_DENIED -> "no_permission";
      case HR_EMPLOYEE_NOT_EMPLOYED -> "not_employed";
      case HR_DAY_HAS_LEAVE -> "on_leave";
      case HR_PERIOD_LOCKED -> "locked";
      case HR_OUTSIDE_SHOP -> "outside_shop";
      case HR_LOCATION_UNCLEAR -> "location_unclear";
      default -> "error";
    };
  }
}
