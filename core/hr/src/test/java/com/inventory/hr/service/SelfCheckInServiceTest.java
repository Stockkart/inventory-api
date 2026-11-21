package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.inventory.hr.rest.dto.response.SelfCheckInResponse;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SelfCheckInServiceTest {

  private static final double SHOP_LAT = 12.9716;
  private static final double SHOP_LNG = 77.5946;
  /** About 33 m north of the shop. */
  private static final double NEAR_LAT = 12.9719;
  /** About 1.1 km north of the shop. */
  private static final double FAR_LAT = 12.9816;

  @Mock private HrSettingsService settingsService;
  @Mock private SelfServiceResolver selfServiceResolver;
  @Mock private HrAccessService hrAccessService;
  @Mock private AttendanceService attendanceService;
  @Mock private PeriodLockService periodLockService;
  @Mock private PunchDayService punchDays;
  @Mock private PunchRepository punchRepository;
  @Mock private MetricsWrapper metrics;
  @InjectMocks private SelfCheckInService service;

  private HrSettings settings;
  private Employee employee;
  private final List<Punch> stored = new ArrayList<>();

  @BeforeEach
  void setUp() {
    settings = HrSettingsService.withDefaults(null, SHOP);
    // weekly off on a day that is not today, so today is a working day
    settings.setWeeklyOffSchedule(new ArrayList<>(List.of(new HrSettings.WeeklyOffEntry(
        "0000-01", List.of(HrDateUtils.today().plusDays(1).getDayOfWeek())))));
    settings.getAttendanceCapture().setSelfCheckIn(true);
    when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);

    employee = new Employee();
    employee.setId("e1");
    employee.setShopId(SHOP);
    employee.setUserId(USER);
    employee.setJoinDate("2020-01-01");
    when(selfServiceResolver.resolve(SHOP, USER)).thenReturn(employee);
    when(hrAccessService.permissions(USER, SHOP))
        .thenReturn(EnumSet.of(HrPermission.SELF_CHECK_IN));
    when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
        anyLong())).thenReturn(List.of());
    when(attendanceService.holidayDates(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(Set.of());
    when(periodLockService.lockedPeriods(anyString(), anyCollection())).thenReturn(Set.of());
    when(punchDays.punchesOf(eq(SHOP), eq("e1"), anyString()))
        .thenAnswer(i -> new ArrayList<>(stored));
    when(punchRepository.insert(any(Punch.class))).thenAnswer(i -> {
      Punch p = i.getArgument(0);
      stored.add(p);
      return p;
    });
  }

  private void locationCheck(OutsideAction action) {
    HrSettings.AttendanceCapture c = settings.getAttendanceCapture();
    c.setLocationCheck(true);
    c.setShopLat(SHOP_LAT);
    c.setShopLng(SHOP_LNG);
    c.setRadiusMetres(100);
    c.setOutsideAction(action);
  }

  private static SelfCheckInRequest at(Double lat, Double lng, Double accuracy) {
    SelfCheckInRequest r = new SelfCheckInRequest();
    r.setLat(lat);
    r.setLng(lng);
    r.setAccuracy(accuracy);
    return r;
  }

  private ErrorCode refused(SelfCheckInRequest request) {
    BaseException e = assertThrows(BaseException.class,
        () -> service.checkIn(SHOP, USER, request));
    verify(punchRepository, never()).insert(any(Punch.class));
    verify(punchDays, never()).writeDerived(any(), any(), any(), any(), any());
    return e.getErrorCode();
  }

  private Punch insertedPunch() {
    ArgumentCaptor<Punch> punch = ArgumentCaptor.forClass(Punch.class);
    verify(punchRepository).insert(punch.capture());
    return punch.getValue();
  }

  // ---- refusals, in the order they are checked ------------------------------------------------

  @Test
  void selfCheckInOffIsRefusedFirst() {
    settings.getAttendanceCapture().setSelfCheckIn(false);
    // even an unlinked login gets "off" first
    when(selfServiceResolver.resolve(SHOP, USER)).thenThrow(
        new BaseException(ErrorCode.HR_NOT_LINKED, ErrorCode.HR_NOT_LINKED.getMessage()));

    assertEquals(ErrorCode.HR_SELF_CHECKIN_OFF, refused(null));
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "off");
  }

  @Test
  void anUnlinkedLoginIsRefused() {
    when(selfServiceResolver.resolve(SHOP, USER)).thenThrow(
        new BaseException(ErrorCode.HR_NOT_LINKED, ErrorCode.HR_NOT_LINKED.getMessage()));

    assertEquals(ErrorCode.HR_NOT_LINKED, refused(null));
  }

  @Test
  void aDatabaseErrorIsNotReportedAsARefusal() {
    settings.getAttendanceCapture().setSelfCheckIn(false);
    when(selfServiceResolver.resolve(SHOP, USER)).thenThrow(
        new BaseException(ErrorCode.HR_QUERY_TOO_SLOW, "slow"));

    assertEquals(ErrorCode.HR_QUERY_TOO_SLOW, refused(null));
  }

  @Test
  void anUnexpectedFailureIsCountedAsError() {
    when(punchRepository.insert(any(Punch.class))).thenThrow(new IllegalStateException("x"));

    assertThrows(IllegalStateException.class, () -> service.checkIn(SHOP, USER, null));
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "error");
  }

  @Test
  void ownPunchesNeedAValidDate() {
    assertThrows(ValidationException.class, () -> service.myPunches(SHOP, USER, "2025-02-30"));
  }

  @Test
  void ownPunchesAreReadForTheCallersOwnEmployeeOnly() {
    service.myPunches(SHOP, USER, "2025-04-08");

    verify(selfServiceResolver).resolve(SHOP, USER);
    verify(punchDays).punchesOf(SHOP, "e1", "2025-04-08");
  }

  @Test
  void aRoleWithoutSelfCheckInIs403() {
    // also what a member whose HR switch is off resolves to: no permissions at all
    when(hrAccessService.permissions(USER, SHOP)).thenReturn(EnumSet.of(
        HrPermission.VIEW_ATTENDANCE));

    assertEquals(ErrorCode.ACCESS_DENIED, refused(null));
    assertEquals(403, ErrorCode.ACCESS_DENIED.getHttpStatus().value());
  }

  @Test
  void anEmployeeNotEmployedTodayIsRefused() {
    employee.setExitDate(HrDateUtils.format(HrDateUtils.today().minusDays(1)));

    assertEquals(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED, refused(null));
  }

  @Test
  void approvedFullDayLeaveTodayIsRefused() {
    LeaveRequest leave = new LeaveRequest();
    leave.setHalfDay(false);
    when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
        anyLong())).thenReturn(List.of(leave));

    assertEquals(ErrorCode.HR_DAY_HAS_LEAVE, refused(null));
  }

  @Test
  void halfDayLeaveTodayStillAllowsACheckIn() {
    LeaveRequest leave = new LeaveRequest();
    leave.setHalfDay(true);
    when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
        anyLong())).thenReturn(List.of(leave));

    service.checkIn(SHOP, USER, null);

    verify(punchRepository).insert(any(Punch.class));
  }

  @Test
  void aFullDayLeaveRangeOverAHolidayDoesNotCoverIt() {
    LeaveRequest leave = new LeaveRequest();
    leave.setHalfDay(false);
    when(attendanceService.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
        anyLong())).thenReturn(List.of(leave));
    when(attendanceService.holidayDates(anyString(), anyString(), anyString(), anyLong()))
        .thenReturn(Set.of(HrDateUtils.format(HrDateUtils.today())));

    service.checkIn(SHOP, USER, null);

    verify(punchRepository).insert(any(Punch.class));
  }

  @Test
  void aLockedSalaryMonthIsRefusedAndNothingIsStored() {
    when(periodLockService.lockedPeriods(anyString(), anyCollection()))
        .thenReturn(Set.of("2025-04"));

    assertEquals(ErrorCode.HR_PERIOD_LOCKED, refused(null));
  }

  // ---- location: BLOCK ------------------------------------------------------------------------

  @Test
  void blockOutsideTheRadiusIsRefused() {
    locationCheck(OutsideAction.BLOCK);

    assertEquals(ErrorCode.HR_OUTSIDE_SHOP, refused(at(FAR_LAT, SHOP_LNG, 10.0)));
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "outside_shop");
  }

  @Test
  void blockWithoutALocationIsTreatedLikeOutside() {
    locationCheck(OutsideAction.BLOCK);

    assertEquals(ErrorCode.HR_OUTSIDE_SHOP, refused(null));
    assertEquals(ErrorCode.HR_OUTSIDE_SHOP, refused(at(NEAR_LAT, null, 10.0)));
  }

  @Test
  void blockWithPoorAccuracyIsLocationUnclear() {
    locationCheck(OutsideAction.BLOCK);

    assertEquals(ErrorCode.HR_LOCATION_UNCLEAR, refused(at(NEAR_LAT, SHOP_LNG, 250.0)));
    assertEquals("Location is not clear, please try again near a window",
        ErrorCode.HR_LOCATION_UNCLEAR.getMessage());
    assertEquals("You're not at the shop", ErrorCode.HR_OUTSIDE_SHOP.getMessage());
  }

  @Test
  void blockWithoutAnAccuracyIsLocationUnclear() {
    locationCheck(OutsideAction.BLOCK);

    assertEquals(ErrorCode.HR_LOCATION_UNCLEAR, refused(at(NEAR_LAT, SHOP_LNG, null)));
  }

  @Test
  void anImpossibleLocationIs400() {
    locationCheck(OutsideAction.BLOCK);

    ValidationException e = assertThrows(ValidationException.class,
        () -> service.checkIn(SHOP, USER, at(91.0, SHOP_LNG, 5.0)));
    // the message never repeats the coordinates
    assertFalse(e.getMessage().contains("91"));
    verify(punchRepository, never()).insert(any(Punch.class));
  }

  @Test
  void insideTheRadiusIsAcceptedWithTheDistanceAndNoFlags() {
    locationCheck(OutsideAction.BLOCK);

    SelfCheckInResponse r = service.checkIn(SHOP, USER, at(NEAR_LAT, SHOP_LNG, 12.4));

    Punch p = insertedPunch();
    assertTrue(p.getFlags().isEmpty());
    assertTrue(p.getDistanceMetres() >= 30 && p.getDistanceMetres() <= 36, "~33 m");
    assertEquals(13, p.getAccuracyMetres());
    assertEquals(p.getDistanceMetres(), r.getDistanceMetres());
    assertEquals(List.of(), r.getFlags());
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "accepted");
  }

  // ---- location: FLAG -------------------------------------------------------------------------

  @Test
  void flagOutsideTheRadiusKeepsThePunchFlagged() {
    locationCheck(OutsideAction.FLAG);

    SelfCheckInResponse r = service.checkIn(SHOP, USER, at(FAR_LAT, SHOP_LNG, 10.0));

    Punch p = insertedPunch();
    assertEquals(List.of(PunchFlag.OUTSIDE_SHOP), p.getFlags());
    assertTrue(p.getDistanceMetres() > 1_000);
    assertEquals(List.of(PunchFlag.OUTSIDE_SHOP), r.getFlags());
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "flagged");
  }

  @Test
  void flagWithPoorAccuracyKeepsThePunchAsLowAccuracy() {
    locationCheck(OutsideAction.FLAG);

    service.checkIn(SHOP, USER, at(NEAR_LAT, SHOP_LNG, 500.0));

    assertEquals(List.of(PunchFlag.LOW_ACCURACY), insertedPunch().getFlags());
  }

  @Test
  void flagWithoutALocationKeepsThePunchAsNoLocation() {
    locationCheck(OutsideAction.FLAG);

    service.checkIn(SHOP, USER, null);

    Punch p = insertedPunch();
    assertEquals(List.of(PunchFlag.NO_LOCATION), p.getFlags());
    assertNull(p.getDistanceMetres());
  }

  @Test
  void withTheLocationCheckOffTheBodyIsIgnored() {
    service.checkIn(SHOP, USER, at(FAR_LAT, SHOP_LNG, 5000.0));

    Punch p = insertedPunch();
    assertNull(p.getDistanceMetres());
    assertNull(p.getAccuracyMetres());
    assertTrue(p.getFlags().isEmpty());
  }

  // ---- the punch ------------------------------------------------------------------------------

  @Test
  void theFirstPunchIsInAtServerTimeAndTheNextOneOut() {
    Instant before = Instant.now().minusSeconds(1);

    SelfCheckInResponse first = service.checkIn(SHOP, USER, null);
    Punch p = stored.get(0);
    assertEquals(PunchDirection.IN, p.getDirection());
    assertEquals(PunchSource.SELF, p.getSource());
    assertFalse(p.getAt().isBefore(before), "server time");
    assertEquals(HrDateUtils.format(HrDateUtils.today()), p.getLocalDate());
    assertEquals("S:e1:" + p.getAt().getEpochSecond(), p.getDedupeKey());
    assertEquals(PunchDirection.OUT, first.getNextDirection());
    assertEquals(1, first.getPunchCount());
    assertEquals(PunchDayDeriver.hhmm(p.getAt()), first.getTodayIn());
    assertNull(first.getTodayOut());

    // a later punch (moved back 10 minutes so it counts)
    p.setAt(p.getAt().minusSeconds(600));
    service.checkIn(SHOP, USER, null);
    assertEquals(PunchDirection.OUT, stored.get(1).getDirection());
  }

  @Test
  void theDerivedDayIsWrittenFromAllPunchesOfToday() {
    service.checkIn(SHOP, USER, null);

    ArgumentCaptor<PunchDayDeriver.Derived> derived =
        ArgumentCaptor.forClass(PunchDayDeriver.Derived.class);
    verify(punchDays).writeDerived(eq(SHOP), eq(employee),
        eq(HrDateUtils.format(HrDateUtils.today())), eq(settings), derived.capture());
    assertEquals(1, derived.getValue().punchCount());
  }

  @Test
  void aSecondTapInTheSameSecondIsADuplicateNotAnError() {
    when(punchRepository.insert(any(Punch.class)))
        .thenThrow(new DuplicateKeyException("E11000"));

    SelfCheckInResponse r = service.checkIn(SHOP, USER, null);

    assertEquals(PunchDirection.IN, r.getNextDirection());
    verify(metrics).increment(HrMetricsConstants.SELF_CHECKIN_TOTAL, 1, "module", "hr",
        "result", "duplicate");
  }

  // ---- privacy --------------------------------------------------------------------------------

  @Test
  void theEmployeesCoordinatesAreNeverInTheStoredPunch() throws Exception {
    locationCheck(OutsideAction.FLAG);

    service.checkIn(SHOP, USER, at(12.123456, 77.654321, 10.0));

    Punch p = insertedPunch();
    for (Field f : Punch.class.getDeclaredFields()) {
      f.setAccessible(true);
      Object value = f.get(p);
      if (value instanceof Number n) {
        assertFalse(Math.abs(n.doubleValue() - 12.123456) < 1e-9
            || Math.abs(n.doubleValue() - 77.654321) < 1e-9, f.getName() + " holds a coordinate");
      }
      assertFalse(f.getName().toLowerCase().contains("lat")
          || f.getName().toLowerCase().contains("lng"), "no coordinate field: " + f.getName());
    }
    assertFalse(p.toString().contains("12.123456"));
    assertFalse(p.toString().contains("77.654321"));
  }

  @Test
  void theRequestNeverPrintsItsCoordinates() {
    String text = at(12.123456, 77.654321, 10.0).toString();

    assertFalse(text.contains("12.123456"));
    assertFalse(text.contains("77.654321"));
  }

  // ---- GET status -----------------------------------------------------------------------------

  @Test
  void theStatusExplainsARefusalInPlainWordsWithout4xx() {
    settings.getAttendanceCapture().setSelfCheckIn(false);

    SelfCheckInResponse r = service.status(SHOP, USER);

    assertFalse(r.isEnabled());
    assertFalse(r.isCanCheckIn());
    assertEquals("Self check-in is turned off for this shop", r.getReason());
    assertEquals(PunchDirection.IN, r.getNextDirection());
  }

  @Test
  void theStatusOfAnUnlinkedLoginSaysSoAndReadsNoPunches() {
    when(selfServiceResolver.resolve(SHOP, USER)).thenThrow(
        new BaseException(ErrorCode.HR_NOT_LINKED, ErrorCode.HR_NOT_LINKED.getMessage()));

    SelfCheckInResponse r = service.status(SHOP, USER);

    assertTrue(r.isEnabled());
    assertFalse(r.isCanCheckIn());
    assertEquals(ErrorCode.HR_NOT_LINKED.getMessage(), r.getReason());
    verify(punchDays, never()).punchesOf(anyString(), anyString(), anyString());
  }

  @Test
  void theStatusShowsTodaysPunchesAndTheLocationRule() {
    locationCheck(OutsideAction.BLOCK);
    stored.add(PunchDayDeriverTest.at("p1", HrDateUtils.today(), "09:00"));
    stored.add(PunchDayDeriverTest.at("p2", HrDateUtils.today(), "13:00"));

    SelfCheckInResponse r = service.status(SHOP, USER);

    assertTrue(r.isCanCheckIn());
    assertNull(r.getReason());
    assertTrue(r.isLocationRequired());
    assertEquals("09:00", r.getTodayIn());
    assertEquals("13:00", r.getTodayOut());
    assertEquals(2, r.getPunchCount());
    assertEquals(PunchDirection.OUT, r.getNextDirection());
    assertNull(r.getFlags());
    assertNull(r.getDistanceMetres());
  }

  @Test
  void distancesAreWorkedOutByHaversine() {
    assertEquals(0, com.inventory.hr.utils.GeoDistance.metres(SHOP_LAT, SHOP_LNG, SHOP_LAT,
        SHOP_LNG), 1e-6);
    // 0.01° of latitude ≈ 1,112 m
    assertEquals(1_112, com.inventory.hr.utils.GeoDistance.metres(SHOP_LAT, SHOP_LNG, FAR_LAT,
        SHOP_LNG), 2);
  }
}
