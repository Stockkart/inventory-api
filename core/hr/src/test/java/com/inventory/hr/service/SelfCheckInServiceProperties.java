// Feature: HR-module, Property 6: Phone-computed location follows the existing rule, and a mock location overrides it
// Feature: HR-module, Property 7: Turning the switch does not change My HR or manual marking
package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.Punch;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.ManualMarkReason;
import com.inventory.hr.domain.model.enums.OutsideAction;
import com.inventory.hr.domain.model.enums.PunchDirection;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.domain.repository.PunchRepository;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.request.SelfCheckInRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.SelfCheckInResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.GeoDistance;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteResult;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Properties 6 and 7 of the HR-module design (task 5.3).
 *
 * <p><b>Property 6 — Validates: Requirements 4.5, 4.6.</b> For any radius, outside action,
 * distance, accuracy (including missing) and mock flag: with {@code mock = false}, {@link
 * SelfCheckInService#locationFromDistance} gives the same refusal or flag set as {@link
 * SelfCheckInService#location} does for coordinates placed at that distance with that accuracy;
 * with {@code mock = true} the result is {@code HR_FAKE_LOCATION} under Block and exactly {@code
 * {FAKE_LOCATION}} with no distance under Allow-and-flag, whatever the distance and accuracy.
 *
 * <p><b>Property 7 — Validates: Requirements 1.2, 7.1.</b> For any settings, employee, leave,
 * holiday, lock and location state and any My HR status or check-in request, the result (refusal
 * or acceptance, flags, stored punch) with {@code biometricApp = true} equals the result with
 * {@code biometricApp = false}; and a hand-made mark of an unpaired employee never needs a reason
 * and saves the same way whatever the switch.
 */
class SelfCheckInServiceProperties {

  /** The sphere {@link GeoDistance} measures on (IUGG mean radius), to invert it along a meridian. */
  private static final double EARTH_RADIUS_METRES = 6_371_008.8;

  // ---- Property 6 ----------------------------------------------------------------------------

  /** What a location rule answered: a refusal code, or the stored distance / accuracy / flags. */
  record LocationOutcome(ErrorCode refusal, Integer distance, Integer accuracy,
      Set<PunchFlag> flags) {
    static LocationOutcome of(HrSettings.AttendanceCapture c, Integer distance, Integer accuracy,
        boolean mock) {
      try {
        return of(SelfCheckInService.locationFromDistance(c, distance, accuracy, mock));
      } catch (BaseException e) {
        return new LocationOutcome(e.getErrorCode(), null, null, null);
      }
    }

    static LocationOutcome of(HrSettings.AttendanceCapture c, SelfCheckInRequest r) {
      try {
        return of(SelfCheckInService.location(c, r));
      } catch (BaseException e) {
        return new LocationOutcome(e.getErrorCode(), null, null, null);
      }
    }

    static LocationOutcome of(SelfCheckInService.Location l) {
      Set<PunchFlag> flags = EnumSet.noneOf(PunchFlag.class);
      flags.addAll(l.flags());
      return new LocationOutcome(null, l.distanceMetres(), l.accuracyMetres(), flags);
    }
  }

  @Property(tries = 300)
  void thePhonesDistanceFollowsTheExistingRuleAndAMockReadingOverridesIt(
      @ForAll("radii") int radius, @ForAll("actions") OutsideAction action,
      @ForAll("distances") int distance, @ForAll("accuracies") Integer accuracy,
      @ForAll boolean mock, @ForAll("latitudes") double shopLat,
      @ForAll("longitudes") double shopLng) {
    HrSettings.AttendanceCapture c = new HrSettings.AttendanceCapture(false, true, true, shopLat,
        shopLng, radius, action);
    boolean flag = action == OutsideAction.FLAG;

    LocationOutcome fromDistance = LocationOutcome.of(c, distance, accuracy, mock);

    if (mock) {
      // a mock reading replaces the rules: Block refuses, Allow-and-flag stores only the flag
      if (!flag) {
        assertEquals(new LocationOutcome(ErrorCode.HR_FAKE_LOCATION, null, null, null),
            fromDistance);
      } else {
        assertEquals(new LocationOutcome(null, null, null, EnumSet.of(PunchFlag.FAKE_LOCATION)),
            fromDistance);
      }
      return;
    }

    // the same point as the phone measured: `distance` metres due north of the shop. Placed a
    // micrometre short so the haversine rounds to `distance` and a point exactly on the radius
    // falls on the same side of `>` as the integer the phone sent (R4.5: the existing rule).
    double lat = shopLat + Math.toDegrees((distance - 1e-6) / EARTH_RADIUS_METRES);
    double exact = GeoDistance.metres(shopLat, shopLng, lat, shopLng);
    assertEquals(distance, Math.round(exact), "the placed point is `distance` metres away");
    SelfCheckInRequest r = new SelfCheckInRequest();
    r.setLat(lat);
    r.setLng(shopLng);
    r.setAccuracy(accuracy == null ? null : accuracy.doubleValue());
    LocationOutcome fromCoordinates = LocationOutcome.of(c, r);

    assertEquals(fromCoordinates, fromDistance, "radius " + radius + ", action " + action
        + ", distance " + distance + ", accuracy " + accuracy);
    // and what both of them must be, by the rule itself
    if (accuracy == null || accuracy > SelfCheckInService.MAX_ACCURACY_METRES) {
      if (!flag) {
        assertEquals(ErrorCode.HR_LOCATION_UNCLEAR, fromDistance.refusal());
        return;
      }
      assertTrue(fromDistance.flags().contains(PunchFlag.LOW_ACCURACY));
    }
    if (distance > radius) {
      if (!flag) {
        assertEquals(ErrorCode.HR_OUTSIDE_SHOP, fromDistance.refusal());
        return;
      }
      assertTrue(fromDistance.flags().contains(PunchFlag.OUTSIDE_SHOP));
    } else if (flag) {
      assertFalse(fromDistance.flags().contains(PunchFlag.OUTSIDE_SHOP));
    }
    assertNull(fromDistance.refusal());
    assertEquals(distance, fromDistance.distance());
    assertEquals(accuracy, fromDistance.accuracy());
  }

  @Provide
  Arbitrary<Integer> radii() {
    return Arbitraries.integers().between(30, 1000);
  }

  /** null = not saved, which the rule treats as Block. */
  @Provide
  Arbitrary<OutsideAction> actions() {
    return Arbitraries.of(OutsideAction.class).injectNull(0.1);
  }

  /** Mostly around the radius range, with the exact edges of every radius reachable. */
  @Provide
  Arbitrary<Integer> distances() {
    return Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 1500)),
        Tuple.of(2, Arbitraries.integers().between(25, 1005)),
        Tuple.of(1, Arbitraries.integers().between(0, 5)));
  }

  @Provide
  Arbitrary<Integer> accuracies() {
    return Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 400)),
        Tuple.of(1, Arbitraries.of(199, 200, 201))).injectNull(0.15);
  }

  @Provide
  Arbitrary<Double> latitudes() {
    return Arbitraries.doubles().between(-80, 80);
  }

  @Provide
  Arbitrary<Double> longitudes() {
    return Arbitraries.doubles().between(-180, 180);
  }

  // ---- Property 7: My HR -----------------------------------------------------------------------

  enum LeaveKind { NONE, FULL_DAY, HALF_DAY }

  enum Call { STATUS, CHECK_IN }

  /**
   * One My HR scenario; everything but the biometric switch. {@code distance} is where the phone
   * is (metres due north of the shop), null = no location sent.
   */
  record Scenario(Call call, boolean selfCheckIn, boolean linked, boolean permitted,
      boolean employed, LeaveKind leave, boolean weekOff, boolean holiday, boolean locked,
      boolean locationCheck, OutsideAction action, int radius, Integer distance,
      Integer accuracy, int punchesToday) {}

  /** A refusal (code and message), or the response fields and the punches the call stored. */
  record SelfResult(ErrorCode code, String message, boolean enabled, boolean locationRequired,
      boolean canCheckIn, String reason, PunchDirection nextDirection, int punchCount,
      List<PunchFlag> flags, Integer distanceMetres, List<StoredPunch> stored) {}

  record StoredPunch(PunchDirection direction, List<PunchFlag> flags, Integer distance,
      Integer accuracy, PunchSource source, String localDate) {}

  @Property(tries = 150)
  void theBiometricSwitchNeverChangesMyHr(@ForAll("scenarios") Scenario s) {
    SelfResult off = new SelfHarness(s, false).run();
    SelfResult on = new SelfHarness(s, true).run();

    assertEquals(off, on, s.toString());
  }

  /** Who is calling and what the My HR checks find. */
  record Who(Call call, boolean selfCheckIn, boolean linked, boolean permitted, boolean employed,
      LeaveKind leave, boolean weekOff, boolean holiday) {}

  /** The lock and the location rule inputs. */
  record Where(boolean locked, boolean locationCheck, OutsideAction action, int radius,
      Integer distance, Integer accuracy, int punchesToday) {}

  @Provide
  Arbitrary<Scenario> scenarios() {
    Arbitrary<Boolean> bool = Arbitraries.of(true, false);
    Arbitrary<Boolean> mostlyTrue = Arbitraries.frequency(Tuple.of(3, true), Tuple.of(1, false));
    Arbitrary<Boolean> rare = Arbitraries.frequency(Tuple.of(3, false), Tuple.of(1, true));
    Arbitrary<Who> who = Combinators.combine(Arbitraries.of(Call.class), mostlyTrue, mostlyTrue,
        mostlyTrue, mostlyTrue, Arbitraries.of(LeaveKind.class), rare, rare).as(Who::new);
    Arbitrary<Integer> distance = Arbitraries.frequencyOf(
        Tuple.of(3, Arbitraries.integers().between(0, 1500)),
        Tuple.of(1, Arbitraries.integers().between(0, 60))).injectNull(0.15);
    Arbitrary<Integer> accuracy = Arbitraries.integers().between(0, 400).injectNull(0.15);
    Arbitrary<Where> where = Combinators.combine(rare, bool, Arbitraries.of(OutsideAction.class),
        Arbitraries.integers().between(30, 1000), distance, accuracy,
        Arbitraries.integers().between(0, 2)).as(Where::new);
    return Combinators.combine(who, where).as((a, b) -> new Scenario(a.call(), a.selfCheckIn(),
        a.linked(), a.permitted(), a.employed(), a.leave(), a.weekOff(), a.holiday(), b.locked(),
        b.locationCheck(), b.action(), b.radius(), b.distance(), b.accuracy(), b.punchesToday()));
  }

  /** The real {@link SelfCheckInService} over mocked reads, built for one scenario and switch. */
  static final class SelfHarness {
    private static final double SHOP_LAT = 12.9716;
    private static final double SHOP_LNG = 77.5946;

    final Scenario s;
    final SelfCheckInService service = new SelfCheckInService();
    final HrSettings settings;
    final Employee employee;
    final List<Punch> stored = new ArrayList<>();
    final LocalDate today = HrDateUtils.today();
    final String d = HrDateUtils.format(today);

    SelfHarness(Scenario s, boolean biometricApp) {
      this.s = s;
      settings = HrSettingsService.withDefaults(null, SHOP);
      HrSettings.AttendanceCapture c = settings.getAttendanceCapture();
      c.setSelfCheckIn(s.selfCheckIn());
      c.setBiometricApp(biometricApp);
      if (s.locationCheck()) {
        c.setLocationCheck(true);
        c.setShopLat(SHOP_LAT);
        c.setShopLng(SHOP_LNG);
        c.setRadiusMetres(s.radius());
        c.setOutsideAction(s.action());
      }
      List<DayOfWeek> off = s.weekOff() ? List.of(today.getDayOfWeek()) : List.of();
      settings.setWeeklyOffSchedule(new ArrayList<>(List.of(
          new HrSettings.WeeklyOffEntry(HrSettingsService.SCHEDULE_START, off))));
      HrSettingsService settingsService = mock(HrSettingsService.class);
      when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);

      employee = new Employee();
      employee.setId("e1");
      employee.setShopId(SHOP);
      employee.setUserId(USER);
      employee.setJoinDate("2020-01-01");
      if (!s.employed()) {
        employee.setExitDate(HrDateUtils.format(today.minusDays(1)));
      }
      SelfServiceResolver resolver = mock(SelfServiceResolver.class);
      if (s.linked()) {
        when(resolver.resolve(SHOP, USER)).thenReturn(employee);
      } else {
        when(resolver.resolve(SHOP, USER)).thenThrow(
            new BaseException(ErrorCode.HR_NOT_LINKED, ErrorCode.HR_NOT_LINKED.getMessage()));
      }
      HrAccessService access = mock(HrAccessService.class);
      when(access.permissions(USER, SHOP)).thenReturn(s.permitted()
          ? EnumSet.of(HrPermission.SELF_CHECK_IN, HrPermission.VIEW_ATTENDANCE)
          : EnumSet.of(HrPermission.VIEW_ATTENDANCE));

      AttendanceService attendance = mock(AttendanceService.class);
      when(attendance.approvedLeave(anyString(), anyCollection(), anyString(), anyString(),
          anyLong())).thenAnswer(i -> {
            if (s.leave() == LeaveKind.NONE) {
              return List.of();
            }
            LeaveRequest l = new LeaveRequest();
            l.setId("leave-1");
            l.setEmployeeId("e1");
            l.setFromDate(i.getArgument(2));
            l.setToDate(i.getArgument(3));
            l.setHalfDay(s.leave() == LeaveKind.HALF_DAY);
            return List.of(l);
          });
      when(attendance.holidayDates(anyString(), anyString(), anyString(), anyLong()))
          .thenAnswer(i -> s.holiday() ? Set.of((String) i.getArgument(1)) : Set.of());
      PeriodLockService locks = mock(PeriodLockService.class);
      when(locks.lockedPeriods(anyString(), anyCollection())).thenAnswer(
          i -> s.locked() ? new HashSet<>(i.<Collection<String>>getArgument(1)) : Set.of());

      // punches already made today, 10 minutes apart from 09:00 IST, so each one counts
      for (int k = 0; k < s.punchesToday(); k++) {
        Punch p = new Punch();
        p.setId("p" + k);
        p.setShopId(SHOP);
        p.setEmployeeId("e1");
        p.setAt(today.atTime(9, 0).atZone(HrDateUtils.ZONE).toInstant().plusSeconds(600L * k));
        p.setLocalDate(d);
        p.setSource(PunchSource.SELF);
        p.setDirection(k == 0 ? PunchDirection.IN : PunchDirection.OUT);
        p.setFlags(new ArrayList<>());
        stored.add(p);
      }
      PunchDayService punchDays = mock(PunchDayService.class);
      when(punchDays.punchesOf(anyString(), anyString(), anyString())).thenAnswer(i -> {
        String date = i.getArgument(2);
        return stored.stream().filter(p -> date.equals(p.getLocalDate())).toList();
      });
      PunchRepository punches = mock(PunchRepository.class);
      when(punches.insert(any(Punch.class))).thenAnswer(i -> {
        Punch p = i.getArgument(0);
        p.setId("new" + stored.size());
        stored.add(p);
        return p;
      });
      when(punches.findByShopIdAndDedupeKey(anyString(), anyString()))
          .thenReturn(Optional.empty());

      ReflectionTestUtils.setField(service, "settingsService", settingsService);
      ReflectionTestUtils.setField(service, "selfServiceResolver", resolver);
      ReflectionTestUtils.setField(service, "hrAccessService", access);
      ReflectionTestUtils.setField(service, "attendanceService", attendance);
      ReflectionTestUtils.setField(service, "periodLockService", locks);
      ReflectionTestUtils.setField(service, "punchDays", punchDays);
      ReflectionTestUtils.setField(service, "punchRepository", punches);
      ReflectionTestUtils.setField(service, "metrics", mock(MetricsWrapper.class));
    }

    SelfResult run() {
      int before = stored.size();
      try {
        SelfCheckInResponse r = s.call() == Call.STATUS ? service.status(SHOP, USER)
            : service.checkIn(SHOP, USER, request());
        List<StoredPunch> added = stored.subList(before, stored.size()).stream()
            .map(p -> new StoredPunch(p.getDirection(), List.copyOf(p.getFlags()),
                p.getDistanceMetres(), p.getAccuracyMetres(), p.getSource(), p.getLocalDate()))
            .toList();
        return new SelfResult(null, null, r.isEnabled(), r.isLocationRequired(),
            r.isCanCheckIn(), r.getReason(), r.getNextDirection(), r.getPunchCount(),
            r.getFlags() == null ? List.of() : List.copyOf(r.getFlags()), r.getDistanceMetres(),
            added);
      } catch (BaseException e) {
        assertEquals(before, stored.size(), "a refusal stores nothing");
        return new SelfResult(e.getErrorCode(), e.getMessage(), false, false, false, null, null,
            0, List.of(), null, List.of());
      }
    }

    private SelfCheckInRequest request() {
      SelfCheckInRequest r = new SelfCheckInRequest();
      if (s.distance() != null) {
        r.setLat(SHOP_LAT + Math.toDegrees((s.distance() - 1e-6)
            / EARTH_RADIUS_METRES));
        r.setLng(SHOP_LNG);
        r.setAccuracy(s.accuracy() == null ? null : s.accuracy().doubleValue());
      }
      return r;
    }
  }

  // ---- Property 7: manual marking ------------------------------------------------------------

  /** One hand-made mark of an unpaired employee; everything but the biometric switch. */
  record Mark(String status, ManualMarkReason reason, String reasonText, int daysAgo,
      AttendanceStatus existing, boolean existingDerived) {}

  /** A refusal, or the rows answered plus what was written and audited. */
  record MarkResult(String refusal, List<RowView> rows, int upserts, int removes, int executes,
      List<String> auditActions, List<String> auditReasons) {}

  record RowView(String employeeId, String date, String status, String markSource,
      boolean markReasonRequired) {}

  @Property(tries = 100)
  void theBiometricSwitchNeverChangesAHandMadeMarkOfAnUnpairedEmployee(@ForAll("marks") Mark m) {
    MarkResult off = new MarkHarness(m, false).run();
    MarkResult on = new MarkHarness(m, true).run();

    assertEquals(off, on, m.toString());
    for (RowView row : on.rows()) {
      assertFalse(row.markReasonRequired(), m + ": an unpaired employee never needs a reason");
    }
    if (on.refusal() != null) {
      assertFalse(on.refusal().equals(AttendanceDayValidator.REASON_REQUIRED),
          m + ": an unpaired employee is never asked for a reason");
    }
  }

  @Provide
  Arbitrary<Mark> marks() {
    Arbitrary<String> status = Arbitraries.of("PRESENT", "ABSENT", "HALF_DAY", "UNMARKED");
    Arbitrary<ManualMarkReason> reason = Arbitraries.of(ManualMarkReason.class).injectNull(0.5);
    Arbitrary<String> text = Arbitraries.of(null, "", "   ", "Sensor broken",
        "x".repeat(200), "x".repeat(201));
    Arbitrary<Integer> daysAgo = Arbitraries.integers().between(0, 20);
    Arbitrary<AttendanceStatus> existing = Arbitraries.of(AttendanceStatus.PRESENT,
        AttendanceStatus.ABSENT, AttendanceStatus.HALF_DAY).injectNull(0.4);
    return Combinators.combine(status, reason, text, daysAgo, existing, Arbitraries.of(true, false))
        .as(Mark::new);
  }

  /**
   * The real {@link AttendanceService#saveDays} over mocked Mongo reads and a counting {@code
   * BulkOperations}, in the style of {@code AttendanceServiceTest}; the pairings read answers
   * "nobody paired".
   */
  static final class MarkHarness {
    final Mark m;
    final AttendanceService service = new AttendanceService();
    final String date;
    final AtomicInteger upserts = new AtomicInteger();
    final AtomicInteger removes = new AtomicInteger();
    final AtomicInteger executes = new AtomicInteger();
    final List<AuditEntry> audits = new ArrayList<>();

    MarkHarness(Mark m, boolean biometricApp) {
      this.m = m;
      date = HrDateUtils.format(HrDateUtils.today().minusDays(m.daysAgo()));

      HrSettings settings = HrSettingsService.withDefaults(null, SHOP);
      settings.getAttendanceCapture().setBiometricApp(biometricApp);
      HrSettingsService settingsService = mock(HrSettingsService.class);
      when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);

      Employee e1 = new Employee();
      e1.setId("e1");
      e1.setShopId(SHOP);
      e1.setCode("EMP-001");
      e1.setName("Ravi Kumar");
      e1.setJoinDate("2020-01-01");

      MongoTemplate mongo = mock(MongoTemplate.class);
      when(mongo.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(e1));
      when(mongo.find(any(Query.class), eq(LeaveRequest.class))).thenReturn(List.of());
      when(mongo.find(any(Query.class), eq(Holiday.class))).thenReturn(List.of());
      List<AttendanceDay> existing = new ArrayList<>();
      if (m.existing() != null) {
        AttendanceDay day = new AttendanceDay();
        day.setShopId(SHOP);
        day.setEmployeeId("e1");
        day.setDate(date);
        day.setStatus(m.existing());
        day.setSource(m.existingDerived() ? AttendanceSource.SELF_CHECK_IN
            : AttendanceSource.MANUAL);
        existing.add(day);
      }
      when(mongo.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(existing);
      BulkOperations bulk = mock(BulkOperations.class);
      when(bulk.upsert(any(Query.class), any(Update.class))).thenAnswer(i -> {
        upserts.incrementAndGet();
        return bulk;
      });
      when(bulk.remove(any(Query.class))).thenAnswer(i -> {
        removes.incrementAndGet();
        return bulk;
      });
      when(bulk.execute()).thenAnswer(i -> {
        executes.incrementAndGet();
        return mock(BulkWriteResult.class);
      });
      when(mongo.bulkOps(any(BulkOperations.BulkMode.class), eq(AttendanceDay.class)))
          .thenReturn(bulk);

      HrAuditService audit = mock(HrAuditService.class);
      doAnswer(i -> {
        audits.addAll(i.<List<AuditEntry>>getArgument(0));
        return null;
      }).when(audit).recordAll(any());
      PunchDayService punchDays = mock(PunchDayService.class);
      when(punchDays.rederive(anyString(), any(), any(), any())).thenReturn(Map.of());
      DevicePairingService pairings = mock(DevicePairingService.class);
      when(pairings.activePairingsByEmployee(anyString(), anyCollection())).thenReturn(Map.of());

      ReflectionTestUtils.setField(service, "mongoTemplate", mongo);
      ReflectionTestUtils.setField(service, "guard", mock(HrPermissionGuard.class));
      ReflectionTestUtils.setField(service, "settingsService", settingsService);
      ReflectionTestUtils.setField(service, "periodLockService", mock(PeriodLockService.class));
      ReflectionTestUtils.setField(service, "auditService", audit);
      ReflectionTestUtils.setField(service, "validator", new AttendanceDayValidator());
      ReflectionTestUtils.setField(service, "queryRunner", new HrQueryRunner());
      ReflectionTestUtils.setField(service, "metrics", mock(MetricsWrapper.class));
      ReflectionTestUtils.setField(service, "userNames", mock(HrUserNames.class));
      ReflectionTestUtils.setField(service, "punchDays", punchDays);
      ReflectionTestUtils.setField(service, "pairingService", pairings);
    }

    MarkResult run() {
      SaveAttendanceDaysRequest.Row row = new SaveAttendanceDaysRequest.Row("e1", date,
          m.status(), null, null, null);
      row.setReason(m.reason());
      row.setReasonText(m.reasonText());
      SaveAttendanceDaysRequest request = new SaveAttendanceDaysRequest(
          new ArrayList<>(List.of(row)));
      try {
        List<AttendanceRowResponse> rows = service.saveDays(USER, SHOP, request);
        return new MarkResult(null, rows.stream().map(r -> new RowView(r.getEmployeeId(),
            r.getDate(), String.valueOf(r.getStatus()), String.valueOf(r.getMarkSource()),
            r.isMarkReasonRequired())).toList(), upserts.get(), removes.get(), executes.get(),
            audits.stream().map(a -> String.valueOf(a.action())).toList(),
            audits.stream().map(AuditEntry::reason).toList());
      } catch (BaseException e) {
        assertEquals(0, executes.get(), "a refusal writes nothing");
        return new MarkResult(e.getMessage(), List.of(), 0, 0, 0, List.of(), List.of());
      }
    }
  }
}
