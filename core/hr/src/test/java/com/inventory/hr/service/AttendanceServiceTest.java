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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.DevicePairing;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceSource;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.ManualMarkReason;
import com.inventory.hr.domain.model.enums.PunchFlag;
import com.inventory.hr.domain.model.enums.PunchSource;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.rest.dto.response.MonthRegisterResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.service.PunchDayService.DaySummary;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.BulkWriteUpsert;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AttendanceServiceTest {

  /** Tuesday and Sunday in a past month. */
  private static final String TUE = "2025-04-08";
  private static final String WED = "2025-04-09";
  private static final String SUN = "2025-04-06";

  @Mock private MongoTemplate mongoTemplate;
  @Mock private HrPermissionGuard guard;
  @Mock private HrSettingsService settingsService;
  @Mock private PeriodLockService periodLockService;
  @Mock private HrAuditService auditService;
  @Mock private MetricsWrapper metrics;
  @Mock private BulkOperations bulk;
  @Mock private HrUserNames userNames;
  @Mock private PunchDayService punchDays;
  @Mock private DevicePairingService pairingService;
  @Spy private AttendanceDayValidator validator = new AttendanceDayValidator();
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private AttendanceService service;

  @BeforeEach
  void setUp() {
    when(settingsService.effectiveSettings(SHOP))
        .thenReturn(HrSettingsService.withDefaults(null, SHOP));
    when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), eq(AttendanceDay.class)))
        .thenReturn(bulk);
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1", "EMP-001"), employee("e2", "EMP-002")));
    when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class))).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of());
    when(mongoTemplate.find(any(Query.class), eq(Holiday.class))).thenReturn(List.of());
  }

  private static Employee employee(String id, String code) {
    Employee e = new Employee();
    e.setId(id);
    e.setShopId(SHOP);
    e.setCode(code);
    e.setName("Name " + code);
    e.setJoinDate("2025-01-01");
    return e;
  }

  private static SaveAttendanceDaysRequest save(SaveAttendanceDaysRequest.Row... rows) {
    return new SaveAttendanceDaysRequest(new ArrayList<>(List.of(rows)));
  }

  private static SaveAttendanceDaysRequest.Row row(String employeeId, String date, String status) {
    return new SaveAttendanceDaysRequest.Row(employeeId, date, status, null, null, null);
  }

  private static AttendanceDay stored(String employeeId, String date, AttendanceStatus status) {
    AttendanceDay d = new AttendanceDay();
    d.setEmployeeId(employeeId);
    d.setDate(date);
    d.setStatus(status);
    return d;
  }

  private static LeaveRequest leave(String employeeId, String from, String to, boolean half) {
    LeaveRequest l = new LeaveRequest();
    l.setId("l-" + employeeId);
    l.setEmployeeId(employeeId);
    l.setFromDate(from);
    l.setToDate(to);
    l.setHalfDay(half);
    return l;
  }

  /** Biometric app check-in on for the shop (R1.1 default is off). */
  private HrSettings biometricAppOn() {
    var settings = HrSettingsService.withDefaults(null, SHOP);
    settings.getAttendanceCapture().setBiometricApp(true);
    when(settingsService.effectiveSettings(SHOP)).thenReturn(settings);
    return settings;
  }

  /**
   * No weekly off at all, for tests that read the register on the real {@code today}: with the
   * default Sunday week-off such a test would resolve the day to WEEK_OFF, not UNMARKED, when it
   * happens to run on a Sunday (Asia/Kolkata).
   */
  private static void noWeekOff(HrSettings settings) {
    settings.setWeeklyOffSchedule(new ArrayList<>(List.of(
        new HrSettings.WeeklyOffEntry(HrSettingsService.SCHEDULE_START, List.of()))));
  }

  /** The given employees have an active paired phone. */
  private void paired(String... employeeIds) {
    Map<String, DevicePairing> pairings = new java.util.LinkedHashMap<>();
    for (String id : employeeIds) {
      DevicePairing p = new DevicePairing();
      p.setId("pair-" + id);
      p.setEmployeeId(id);
      p.setPhoneModel("Redmi Note 11");
      pairings.put(id, p);
    }
    when(pairingService.activePairingsByEmployee(eq(SHOP), anyCollection())).thenReturn(pairings);
  }

  private static SaveAttendanceDaysRequest.Row withReason(SaveAttendanceDaysRequest.Row r,
      ManualMarkReason reason, String text) {
    r.setReason(reason);
    r.setReasonText(text);
    return r;
  }

  @SuppressWarnings("unchecked")
  private List<AuditEntry> recordedAudit() {
    ArgumentCaptor<List<AuditEntry>> audit = ArgumentCaptor.forClass(List.class);
    verify(auditService).recordAll(audit.capture());
    return audit.getValue();
  }

  @Test
  void anEmployeeOfAnotherShopIs404AndNothingIsWritten() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of());

    assertThrows(ResourceNotFoundException.class,
        () -> service.saveDays(USER, SHOP, save(row("foreign", TUE, "PRESENT"))));
    verify(bulk, never()).execute();
    verify(guard).require(USER, SHOP, HrPermission.MARK_ATTENDANCE);
  }

  @Test
  void aLockedMonthRejectsTheWholeSave() {
    doThrow(new BaseException(ErrorCode.HR_PERIOD_LOCKED, "locked"))
        .when(periodLockService).assertOpen(eq(SHOP), anyCollection());

    BaseException e = assertThrows(BaseException.class,
        () -> service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT"))));
    assertEquals(ErrorCode.HR_PERIOD_LOCKED, e.getErrorCode());
    verify(bulk, never()).execute();
  }

  @Test
  void aFullDayLeaveDayCannotBeMarked() {
    when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class)))
        .thenReturn(List.of(leave("e1", "2025-04-07", WED, false)));

    BaseException e = assertThrows(BaseException.class,
        () -> service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT"))));
    assertEquals(ErrorCode.HR_DAY_HAS_LEAVE, e.getErrorCode());
  }

  @Test
  void aSundayInsideALeaveRangeAndAHalfDayLeaveDayCanBeMarked() {
    when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class))).thenReturn(List.of(
        leave("e1", "2025-04-05", "2025-04-07", false), leave("e2", TUE, TUE, true)));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    List<AttendanceRowResponse> rows = service.saveDays(USER, SHOP,
        save(row("e1", SUN, "PRESENT"), row("e2", TUE, "PRESENT")));

    assertEquals(DayStatus.PRESENT, rows.get(0).getStatus());
    assertEquals(DayStatus.LEAVE, rows.get(1).getStatus());
    assertEquals(DayStatus.PRESENT, rows.get(1).getOtherHalf());
    verify(bulk, times(2)).upsert(any(Query.class), any(Update.class));
  }

  @Test
  void outsideEmploymentIs422() {
    Employee late = employee("e1", "EMP-001");
    late.setJoinDate("2025-04-10");
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(late));

    BaseException e = assertThrows(BaseException.class,
        () -> service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT"))));
    assertEquals(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED, e.getErrorCode());
  }

  @SuppressWarnings("unchecked")
  @Test
  void firstMarksGetOneSummaryAndChangedMarksOneEventEach() {
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of(
        stored("e2", TUE, AttendanceStatus.ABSENT), stored("e1", WED, AttendanceStatus.PRESENT)));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT"), row("e2", TUE, "PRESENT"),
        row("e1", WED, "PRESENT"), row("e2", WED, "UNMARKED")));

    // e1/TUE new, e2/TUE changed, e1/WED unchanged (skipped), e2/WED nothing stored (skipped)
    verify(bulk, times(2)).upsert(any(Query.class), any(Update.class));
    verify(bulk, never()).remove(any(Query.class));
    verify(bulk, times(1)).execute();
    ArgumentCaptor<List<AuditEntry>> audit = ArgumentCaptor.forClass(List.class);
    verify(auditService).recordAll(audit.capture());
    List<AuditEntry> entries = audit.getValue();
    assertEquals(2, entries.size());
    assertEquals(HrAuditAction.ATTENDANCE_BULK_SET, entries.get(0).action());
    assertEquals(1, entries.get(0).after().get("count"));
    assertEquals(HrAuditAction.ATTENDANCE_SET, entries.get(1).action());
    assertEquals("ABSENT", entries.get(1).before().get("status"));
    assertEquals("PRESENT", entries.get(1).after().get("status"));
  }

  @Test
  void clearingAStoredMarkRemovesItAndAuditsTheBefore() {
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.ABSENT)));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    List<AttendanceRowResponse> rows =
        service.saveDays(USER, SHOP, save(row("e1", TUE, "UNMARKED")));

    verify(bulk).remove(any(Query.class));
    assertEquals(DayStatus.UNMARKED, rows.get(0).getStatus());
  }

  @SuppressWarnings("unchecked")
  @Test
  void clearingAHandMadeMarkReDerivesTheDayFromPunches() {
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.ABSENT)));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));
    AttendanceDay derived = stored("e1", TUE, AttendanceStatus.PRESENT);
    derived.setSource(AttendanceSource.SELF_CHECK_IN);
    derived.setInTime("09:05");
    derived.setPunchCount(1);
    when(punchDays.rederive(eq(SHOP), any(), anyCollection(), any()))
        .thenReturn(Map.of(AttendanceService.key("e1", TUE), derived));

    List<AttendanceRowResponse> rows =
        service.saveDays(USER, SHOP, save(row("e1", TUE, "UNMARKED")));

    ArgumentCaptor<Collection<String[]>> keys = ArgumentCaptor.forClass(Collection.class);
    verify(punchDays).rederive(eq(SHOP), any(), keys.capture(), any());
    assertEquals(1, keys.getValue().size());
    assertEquals("e1", keys.getValue().iterator().next()[0]);
    assertEquals(DayStatus.PRESENT, rows.get(0).getStatus());
    assertEquals(AttendanceSource.SELF_CHECK_IN, rows.get(0).getMarkSource());
    assertEquals("09:05", rows.get(0).getInTime());
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceSource.class, names = {"SELF_CHECK_IN", "BIOMETRIC_APP"})
  void clearingADerivedDayDoesNotReDeriveIt(AttendanceSource source) {
    AttendanceDay self = stored("e1", TUE, AttendanceStatus.PRESENT);
    self.setSource(source);
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of(self));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(row("e1", TUE, "UNMARKED")));

    verify(bulk).remove(any(Query.class));
    verify(punchDays, never()).rederive(any(), any(), anyCollection(), any());
  }

  @Test
  void aSaveWithoutClearsReadsNoPunches() {
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT")));

    verify(punchDays, never()).rederive(any(), any(), anyCollection(), any());
  }

  @ParameterizedTest
  @EnumSource(value = AttendanceSource.class, names = {"SELF_CHECK_IN", "BIOMETRIC_APP"})
  void savingTheSameValuesOverADerivedDayMakesItHandMade(AttendanceSource source) {
    AttendanceDay self = stored("e1", TUE, AttendanceStatus.PRESENT);
    self.setSource(source);
    self.setPunchCount(2);
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of(self));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT")));

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(bulk).upsert(any(Query.class), update.capture());
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertEquals(AttendanceSource.MANUAL, set.get("source"));
    Document unset = (Document) update.getValue().getUpdateObject().get("$unset");
    assertTrue(unset.containsKey("punchCount"));
    assertTrue(unset.containsKey("missingCheckOut"));
  }

  @Test
  void theDailyRegisterShowsMarkSourceMissingCheckOutAndPunchFlags() {
    String yesterday = HrDateUtils.format(HrDateUtils.today().minusDays(1));
    AttendanceDay self = stored("e1", yesterday, AttendanceStatus.PRESENT);
    self.setSource(AttendanceSource.SELF_CHECK_IN);
    self.setInTime("09:00");
    self.setPunchCount(1);
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of(self,
        stored("e2", yesterday, AttendanceStatus.ABSENT)));
    when(punchDays.summaryByEmployeeDate(eq(SHOP), anyCollection(), eq(yesterday),
        org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Map.of(AttendanceService.key("e1", yesterday),
            new DaySummary(EnumSet.of(PunchFlag.OUTSIDE_SHOP), PunchSource.SELF, 1)));

    DailyRegisterResponse r = service.daily(USER, SHOP, yesterday);

    AttendanceRowResponse e1 = r.getRows().get(0);
    assertEquals(AttendanceSource.SELF_CHECK_IN, e1.getMarkSource());
    assertTrue(e1.isMissingCheckOut(), "one punch on a past day");
    assertEquals(List.of(PunchFlag.OUTSIDE_SHOP), e1.getFlags());
    assertEquals(PunchSource.SELF, e1.getPunchSource());
    AttendanceRowResponse e2 = r.getRows().get(1);
    // a mark stored before sources existed reads as MANUAL
    assertEquals(AttendanceSource.MANUAL, e2.getMarkSource());
    assertFalse(e2.isMissingCheckOut());
    assertTrue(e2.getFlags().isEmpty());
    assertNull(e2.getPunchSource());
    // R7.1: switch off, so the pairings are not even read
    verify(pairingService, never()).activePairingsByEmployee(any(), anyCollection());
    assertFalse(e1.isMarkReasonRequired());
    assertFalse(e1.isNoPunchYet());
  }

  @Test
  void monthCellsCarryTheDerivedSourceButNotManualAndReadNoPunches() {
    AttendanceDay self = stored("e1", "2025-04-08", AttendanceStatus.PRESENT);
    self.setSource(AttendanceSource.SELF_CHECK_IN);
    self.setPunchCount(1);
    AttendanceDay app = stored("e1", "2025-04-10", AttendanceStatus.PRESENT);
    app.setSource(AttendanceSource.BIOMETRIC_APP);
    app.setPunchCount(1);
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class))).thenReturn(List.of(self,
        stored("e1", "2025-04-09", AttendanceStatus.PRESENT), app));

    MonthRegisterResponse m = service.month(USER, SHOP, "2025-04", "e1");

    MonthRegisterResponse.Cell tue = m.getRows().get(0).getCells().get(7);
    assertEquals(AttendanceSource.SELF_CHECK_IN, tue.getMarkSource());
    assertEquals(Boolean.TRUE, tue.getMissingCheckOut());
    MonthRegisterResponse.Cell wed = m.getRows().get(0).getCells().get(8);
    assertNull(wed.getMarkSource());
    assertNull(wed.getMissingCheckOut());
    // R8.1: the fingerprint icon comes from the stored day; the month register reads no punches
    MonthRegisterResponse.Cell thu = m.getRows().get(0).getCells().get(9);
    assertEquals(AttendanceSource.BIOMETRIC_APP, thu.getMarkSource());
    assertEquals(Boolean.TRUE, thu.getMissingCheckOut());
    assertNull(thu.getFlags());
    verify(punchDays, never()).summaryByEmployeeDate(any(), anyCollection(), any(),
        org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  void aPresentDayWithFewHoursIsStoredAsAnAutomaticHalfDay() {
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));
    SaveAttendanceDaysRequest.Row r = row("e1", TUE, "PRESENT");
    r.setInTime("09:00");
    r.setOutTime("11:00");

    service.saveDays(USER, SHOP, save(r));

    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(bulk).upsert(any(Query.class), update.capture());
    Document set = (Document) update.getValue().getUpdateObject().get("$set");
    assertEquals(AttendanceStatus.HALF_DAY, set.get("status"));
    assertEquals(Boolean.TRUE, set.get("autoHalfDay"));
    assertEquals(120, set.get("workedMinutes"));
    assertEquals(USER, set.get("markedByUserId"));
  }

  @Test
  void markUnmarkedPresentSkipsMarkedLeaveHolidayAndWeekOffAndOnlySetsOnInsert() {
    when(mongoTemplate.find(any(Query.class), eq(Holiday.class))).thenReturn(List.of(
        holiday("2025-04-09")));
    when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class)))
        .thenReturn(List.of(leave("e2", "2025-04-07", "2025-04-07", false)));
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", "2025-04-07", AttendanceStatus.ABSENT)));
    BulkWriteResult result = mock(BulkWriteResult.class);
    when(result.getUpserts()).thenReturn(List.of(new BulkWriteUpsert(0, new BsonObjectId()),
        new BulkWriteUpsert(1, new BsonObjectId())));
    when(bulk.execute()).thenReturn(result);

    // Sun 6 (week-off), Mon 7 (e1 marked, e2 on leave), Tue 8 (both unmarked), Wed 9 (holiday)
    int marked = service.markUnmarkedPresent(USER, SHOP, new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of(SUN, "2025-04-07", TUE, WED)), null)).getMarked();

    assertEquals(2, marked);
    ArgumentCaptor<Update> updates = ArgumentCaptor.forClass(Update.class);
    verify(bulk, times(2)).upsert(any(Query.class), updates.capture());
    for (Update u : updates.getAllValues()) {
      Document doc = u.getUpdateObject();
      assertEquals(Set.of("$setOnInsert"), doc.keySet(), "never overwrites an existing day");
    }
    verify(periodLockService).assertOpen(SHOP, Set.of("2025-04"));
    verify(auditService).recordAll(anyList());
  }

  @Test
  void theDailyRegisterReadsEachCollectionOnce() {
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.PRESENT)));
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of());

    DailyRegisterResponse r = service.daily(USER, SHOP, TUE);

    assertEquals(2, r.getRows().size());
    assertEquals(DayStatus.PRESENT, r.getRows().get(0).getStatus());
    assertEquals(DayStatus.UNMARKED, r.getRows().get(1).getStatus());
    assertFalse(r.isLocked());
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(Employee.class));
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(AttendanceDay.class));
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(LeaveRequest.class));
    verify(mongoTemplate, times(1)).find(any(Query.class), eq(Holiday.class));
    verify(settingsService, times(1)).effectiveSettings(SHOP);
    verify(periodLockService, times(1)).lockedPeriods(eq(SHOP), anyCollection());
    verify(guard).require(USER, SHOP, HrPermission.VIEW_ATTENDANCE);
  }

  @Test
  void theMonthRegisterCountsTotals() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1", "EMP-001")));
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.PRESENT)));
    when(mongoTemplate.find(any(Query.class), eq(LeaveRequest.class)))
        .thenReturn(List.of(leave("e1", WED, WED, true)));
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of("2025-04"));

    var month = service.month(USER, SHOP, "2025-04", null);

    assertTrue(month.isLocked());
    assertEquals(30, month.getDates().size());
    var totals = month.getRows().get(0).getTotals();
    assertEquals(0, totals.getPresent().compareTo(java.math.BigDecimal.ONE));
    assertEquals(0, totals.getLeave().compareTo(new java.math.BigDecimal("0.5")));
    // April 2025 has 4 Sundays; 30 − 4 − 1 present − 1 leave day = 24 unmarked + half of the 9th
    assertEquals(0, totals.getWeekOff().compareTo(new java.math.BigDecimal("4")));
    assertEquals(0, totals.getUnmarked().compareTo(new java.math.BigDecimal("24.5")));
  }

  @Test
  void historyNamesTheFirstMarkerAndEveryActorInOneLookup() {
    AttendanceDay day = stored("e1", TUE, AttendanceStatus.PRESENT);
    day.setMarkedByUserId("u-first");
    com.inventory.hr.domain.model.HrAuditEvent change =
        new com.inventory.hr.domain.model.HrAuditEvent();
    change.setActorUserId("u-second");
    when(mongoTemplate.exists(any(Query.class), eq(Employee.class))).thenReturn(true);
    when(mongoTemplate.findOne(any(Query.class), eq(AttendanceDay.class))).thenReturn(day);
    when(mongoTemplate.find(any(Query.class),
        eq(com.inventory.hr.domain.model.HrAuditEvent.class))).thenReturn(List.of(change));
    when(userNames.namesOf(org.mockito.ArgumentMatchers.anyCollection()))
        .thenReturn(java.util.Map.of("u-first", "Asha", "u-second", "Mohan"));

    var history = service.history(USER, SHOP, "e1", TUE);

    assertEquals("Asha", history.getActorName());
    assertEquals("Mohan", history.getEvents().get(0).getActorName());
    verify(userNames, org.mockito.Mockito.times(1))
        .namesOf(org.mockito.ArgumentMatchers.anyCollection());
  }

  private static Holiday holiday(String date) {
    Holiday h = new Holiday();
    h.setDate(date);
    return h;
  }
  @Test
  void daysAfterTodayAreFlaggedAndNotCountedAsUnmarked() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1", "EMP-001")));
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of());
    java.time.LocalDate today = com.inventory.hr.utils.HrDateUtils.today();
    java.time.YearMonth current = java.time.YearMonth.from(today);
    var settings = HrSettingsService.withDefaults(null, SHOP);
    int expected = 0;
    for (java.time.LocalDate d = current.atDay(1); !d.isAfter(today); d = d.plusDays(1)) {
      if (!DayStatusResolver.isWeekOff(d, settings)) {
        expected++;
      }
    }

    var month = service.month(USER, SHOP, current.toString(), null);

    var row = month.getRows().get(0);
    // only the working days up to today are Unmarked; the rest of the month has not happened
    assertEquals(0, row.getTotals().getUnmarked().compareTo(java.math.BigDecimal.valueOf(expected)));
    for (int i = 0; i < month.getDates().size(); i++) {
      boolean future = java.time.LocalDate.parse(month.getDates().get(i)).isAfter(today);
      assertEquals(future ? Boolean.TRUE : null, row.getCells().get(i).getFuture(),
          month.getDates().get(i));
    }
  }

  @Test
  void aMonthAfterTodayHasNoUnmarkedDays() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1", "EMP-001")));
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of());
    String next = java.time.YearMonth.from(com.inventory.hr.utils.HrDateUtils.today())
        .plusMonths(1).toString();

    var row = service.month(USER, SHOP, next, null).getRows().get(0);

    assertEquals(0, row.getTotals().getUnmarked().signum());
    assertTrue(row.getCells().stream().allMatch(c -> Boolean.TRUE.equals(c.getFuture())));
    assertTrue(row.getCells().stream().anyMatch(c -> c.getStatus() == DayStatus.UNMARKED));
  }

  @Test
  void aPastUnmarkedDayIsStillCountedAndNotFlagged() {
    when(mongoTemplate.find(any(Query.class), eq(Employee.class)))
        .thenReturn(List.of(employee("e1", "EMP-001")));
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of());

    var row = service.month(USER, SHOP, "2025-04", null).getRows().get(0);

    // April 2025: 30 days − 4 Sundays, nothing marked
    assertEquals(0, row.getTotals().getUnmarked().compareTo(new java.math.BigDecimal("26")));
    assertTrue(row.getCells().stream().allMatch(c -> c.getFuture() == null));
  }

  @Test
  void theUnmarkedOtherHalfOfAFutureHalfDayLeaveIsNotCounted() {
    var halfLeave = new DayStatusResolver.Resolution(DayStatus.LEAVE,
        DayStatusResolver.Source.LEAVE, true, DayStatus.UNMARKED, "l1", false, null, 0, false);
    var future = new com.inventory.hr.rest.dto.response.MonthRegisterResponse.Totals();
    var past = new com.inventory.hr.rest.dto.response.MonthRegisterResponse.Totals();

    AttendanceService.addToTotals(future, halfLeave, true);
    AttendanceService.addToTotals(past, halfLeave, false);

    assertEquals(0, future.getUnmarked().signum());
    assertEquals(0, future.getLeave().compareTo(new java.math.BigDecimal("0.5")));
    assertEquals(0, past.getUnmarked().compareTo(new java.math.BigDecimal("0.5")));
  }

  // ---- R7: hand-made marks of paired employees need a reason ------------------------------------

  @Test
  void withTheSwitchOffAPairedEmployeeNeedsNoReasonAndPairingsAreNotRead() {
    paired("e1");
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT")));

    verify(bulk).execute();
    verify(pairingService, never()).activePairingsByEmployee(any(), anyCollection());
  }

  @Test
  void withTheSwitchOnAnUnpairedEmployeeNeedsNoReason() {
    biometricAppOn();
    paired();
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    List<AttendanceRowResponse> rows =
        service.saveDays(USER, SHOP, save(row("e1", TUE, "PRESENT")));

    verify(bulk).execute();
    verify(pairingService, times(1)).activePairingsByEmployee(eq(SHOP), anyCollection());
    assertFalse(rows.get(0).isMarkReasonRequired());
  }

  @Test
  void aPairedEmployeesMarkWithoutAReasonIsRefusedAndNothingIsWritten() {
    biometricAppOn();
    paired("e2");

    ValidationException e = assertThrows(ValidationException.class, () -> service.saveDays(USER,
        SHOP, save(row("e1", TUE, "PRESENT"), row("e2", TUE, "PRESENT"))));

    assertEquals("A reason is required when the employee uses the Attendance app",
        e.getMessage());
    verify(bulk, never()).upsert(any(Query.class), any(Update.class));
    verify(bulk, never()).execute();
    verify(auditService, never()).recordAll(anyList());
  }

  @Test
  void clearingAPairedEmployeesMarkAlsoNeedsAReason() {
    biometricAppOn();
    paired("e1");
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.ABSENT)));

    assertThrows(ValidationException.class,
        () -> service.saveDays(USER, SHOP, save(row("e1", TUE, "UNMARKED"))));
    verify(bulk, never()).execute();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "\t\n"})
  void otherWithEmptyOrWhitespaceOnlyTextIsNoReason(String text) {
    biometricAppOn();
    paired("e1");

    assertThrows(ValidationException.class, () -> service.saveDays(USER, SHOP,
        save(withReason(row("e1", TUE, "PRESENT"), ManualMarkReason.OTHER, text))));
    verify(bulk, never()).execute();
  }

  @Test
  void otherWithNoTextIsNoReason() {
    biometricAppOn();
    paired("e1");

    assertThrows(ValidationException.class, () -> service.saveDays(USER, SHOP,
        save(withReason(row("e1", TUE, "PRESENT"), ManualMarkReason.OTHER, null))));
    verify(bulk, never()).execute();
  }

  @Test
  void otherTextOver200CharactersCannotBeSaved() {
    biometricAppOn();
    paired("e1");

    ValidationException e = assertThrows(ValidationException.class, () -> service.saveDays(USER,
        SHOP, save(withReason(row("e1", TUE, "PRESENT"), ManualMarkReason.OTHER,
            "x".repeat(201)))));

    assertTrue(e.getMessage().contains("200"), e.getMessage());
    verify(bulk, never()).execute();
  }

  @Test
  void otherTextOfExactly200CharactersAfterTrimIsAccepted() {
    biometricAppOn();
    paired("e1");
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(withReason(row("e1", TUE, "PRESENT"),
        ManualMarkReason.OTHER, "  " + "x".repeat(200) + "  ")));

    verify(bulk).execute();
    List<AuditEntry> entries = recordedAudit();
    assertEquals(1, entries.size());
    assertEquals("OTHER: " + "x".repeat(200), entries.get(0).reason());
  }

  @Test
  void aQuickPickNeedsNoTextAndIsRecordedByName() {
    biometricAppOn();
    paired("e1");
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    List<AttendanceRowResponse> rows = service.saveDays(USER, SHOP,
        save(withReason(row("e1", TUE, "PRESENT"), ManualMarkReason.FORGOT_PHONE, null)));

    verify(bulk).execute();
    List<AuditEntry> entries = recordedAudit();
    // R7.5: a first mark with a reason gets its own row, not the bulk summary
    assertEquals(1, entries.size());
    assertEquals(HrAuditAction.ATTENDANCE_SET, entries.get(0).action());
    assertEquals("FORGOT_PHONE", entries.get(0).reason());
    assertEquals("e1", entries.get(0).employeeId());
    assertEquals(TUE, entries.get(0).date());
    assertNull(entries.get(0).before());
    assertEquals("PRESENT", entries.get(0).after().get("status"));
    assertTrue(rows.get(0).isMarkReasonRequired());
  }

  @Test
  void theReasonRowCarriesTheDerivedValuesItReplaced() {
    biometricAppOn();
    paired("e1");
    AttendanceDay derived = stored("e1", TUE, AttendanceStatus.PRESENT);
    derived.setSource(AttendanceSource.BIOMETRIC_APP);
    derived.setInTime("09:05");
    derived.setOutTime("18:10");
    derived.setPunchCount(2);
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(derived));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));
    SaveAttendanceDaysRequest.Row r = row("e1", TUE, "PRESENT");
    r.setInTime("09:00");
    r.setOutTime("18:00");

    service.saveDays(USER, SHOP, save(withReason(r, ManualMarkReason.OTHER, " Sensor broken ")));

    List<AuditEntry> entries = recordedAudit();
    assertEquals(1, entries.size());
    AuditEntry entry = entries.get(0);
    assertEquals(HrAuditAction.ATTENDANCE_SET, entry.action());
    assertEquals("OTHER: Sensor broken", entry.reason());
    // R7.6: what the punches had derived, before the hand-made values replaced them
    assertEquals("PRESENT", entry.before().get("status"));
    assertEquals("09:05", entry.before().get("inTime"));
    assertEquals("18:10", entry.before().get("outTime"));
    assertEquals("BIOMETRIC_APP", entry.before().get("source"));
    assertEquals("09:00", entry.after().get("inTime"));
    assertEquals("MANUAL", entry.after().get("source"));
  }

  @Test
  void clearingWithAReasonRecordsIt() {
    biometricAppOn();
    paired("e1");
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e1", TUE, AttendanceStatus.ABSENT)));
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(withReason(row("e1", TUE, "UNMARKED"),
        ManualMarkReason.NO_NETWORK, null)));

    verify(bulk).remove(any(Query.class));
    List<AuditEntry> entries = recordedAudit();
    assertEquals(1, entries.size());
    assertEquals("NO_NETWORK", entries.get(0).reason());
    assertEquals("ABSENT", entries.get(0).before().get("status"));
    assertNull(entries.get(0).after());
  }

  @Test
  void rowsWithoutAReasonKeepTheExistingAuditShapeNextToRowsWithOne() {
    biometricAppOn();
    paired("e1");
    when(bulk.execute()).thenReturn(mock(BulkWriteResult.class));

    service.saveDays(USER, SHOP, save(
        withReason(row("e1", TUE, "PRESENT"), ManualMarkReason.PHONE_NOT_WORKING, null),
        row("e2", TUE, "PRESENT")));

    List<AuditEntry> entries = recordedAudit();
    assertEquals(2, entries.size());
    assertEquals(HrAuditAction.ATTENDANCE_BULK_SET, entries.get(0).action());
    assertEquals(1, entries.get(0).after().get("count"), "only the row without a reason");
    assertEquals(HrAuditAction.ATTENDANCE_SET, entries.get(1).action());
    assertEquals("PHONE_NOT_WORKING", entries.get(1).reason());
  }

  @Test
  void markUnmarkedPresentRefusesTheWholeBatchWhenAPairedEmployeeHasNoReason() {
    biometricAppOn();
    paired("e2");

    ValidationException e = assertThrows(ValidationException.class,
        () -> service.markUnmarkedPresent(USER, SHOP,
            new MarkUnmarkedPresentRequest(new ArrayList<>(List.of(TUE)), null)));

    assertEquals("A reason is required when the employee uses the Attendance app",
        e.getMessage());
    verify(bulk, never()).execute();
    verify(auditService, never()).recordAll(anyList());
  }

  @Test
  void markUnmarkedPresentWithoutPairedEmployeesNeedsNoReason() {
    biometricAppOn();
    paired();
    BulkWriteResult result = mock(BulkWriteResult.class);
    when(result.getUpserts()).thenReturn(List.of(new BulkWriteUpsert(0, new BsonObjectId()),
        new BulkWriteUpsert(1, new BsonObjectId())));
    when(bulk.execute()).thenReturn(result);

    int marked = service.markUnmarkedPresent(USER, SHOP,
        new MarkUnmarkedPresentRequest(new ArrayList<>(List.of(TUE)), null)).getMarked();

    assertEquals(2, marked);
    List<AuditEntry> entries = recordedAudit();
    assertEquals(1, entries.size());
    assertEquals(HrAuditAction.ATTENDANCE_BULK_SET, entries.get(0).action());
    assertEquals(2, entries.get(0).after().get("count"));
  }

  @Test
  void markUnmarkedPresentWritesOneReasonRowPerPairedEmployeeDay() {
    biometricAppOn();
    paired("e1");
    BulkWriteResult result = mock(BulkWriteResult.class);
    // bulk order: e1/TUE (0), e1/WED (1), e2/TUE (2), e2/WED (3); e2/WED lost a race
    when(result.getUpserts()).thenReturn(List.of(new BulkWriteUpsert(0, new BsonObjectId()),
        new BulkWriteUpsert(1, new BsonObjectId()), new BulkWriteUpsert(2, new BsonObjectId())));
    when(bulk.execute()).thenReturn(result);

    int marked = service.markUnmarkedPresent(USER, SHOP, new MarkUnmarkedPresentRequest(
        new ArrayList<>(List.of(TUE, WED)), null, ManualMarkReason.OTHER, " App crashed "))
        .getMarked();

    assertEquals(3, marked);
    List<AuditEntry> entries = recordedAudit();
    assertEquals(3, entries.size());
    assertEquals(HrAuditAction.ATTENDANCE_BULK_SET, entries.get(0).action());
    assertEquals(1, entries.get(0).after().get("count"), "e2/TUE only");
    for (AuditEntry entry : entries.subList(1, 3)) {
      assertEquals(HrAuditAction.ATTENDANCE_SET, entry.action());
      assertEquals("e1", entry.employeeId());
      assertEquals("OTHER: App crashed", entry.reason());
      assertEquals("PRESENT", entry.after().get("status"));
      assertEquals("BULK_PRESENT", entry.after().get("source"));
      assertNull(entry.before());
    }
    assertEquals(Set.of(TUE, WED),
        Set.of(entries.get(1).date(), entries.get(2).date()));
  }

  // ---- R7.7–R7.8, R7.2 on the registers ------------------------------------------------------

  @Test
  void noPunchYetShowsForTodayOnlyWhenPairedSwitchOnNoPunchAndNoMark() {
    // no week-off: today may be a Sunday, and the hint is asserted against UNMARKED below
    noWeekOff(biometricAppOn());
    String today = HrDateUtils.format(HrDateUtils.today());
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(
        employee("e1", "EMP-001"), employee("e2", "EMP-002"), employee("e3", "EMP-003"),
        employee("e4", "EMP-004")));
    // e2 has a (duplicate-only) punch, e3 is not paired, e4 is marked by hand
    when(punchDays.summaryByEmployeeDate(eq(SHOP), anyCollection(), eq(today),
        org.mockito.ArgumentMatchers.anyLong()))
        .thenReturn(Map.of(AttendanceService.key("e2", today),
            new DaySummary(EnumSet.noneOf(PunchFlag.class), null, 1)));
    when(mongoTemplate.find(any(Query.class), eq(AttendanceDay.class)))
        .thenReturn(List.of(stored("e4", today, AttendanceStatus.PRESENT)));
    paired("e1", "e2", "e4");

    DailyRegisterResponse r = service.daily(USER, SHOP, today);

    assertTrue(r.getRows().get(0).isNoPunchYet(), "e1: paired, nothing yet");
    assertFalse(r.getRows().get(1).isNoPunchYet(), "e2: has a punch");
    assertFalse(r.getRows().get(2).isNoPunchYet(), "e3: not paired");
    assertFalse(r.getRows().get(3).isNoPunchYet(), "e4: marked by hand");
    assertTrue(r.getRows().get(0).isMarkReasonRequired());
    assertTrue(r.getRows().get(1).isMarkReasonRequired());
    assertFalse(r.getRows().get(2).isMarkReasonRequired());
    assertEquals(DayStatus.UNMARKED, r.getRows().get(0).getStatus(), "the hint is not a status");
    verify(pairingService, times(1)).activePairingsByEmployee(eq(SHOP), anyCollection());
  }

  @Test
  void noPunchYetNeverShowsForAPastDate() {
    biometricAppOn();
    paired("e1");
    String yesterday = HrDateUtils.format(HrDateUtils.today().minusDays(1));

    DailyRegisterResponse r = service.daily(USER, SHOP, yesterday);

    assertFalse(r.getRows().get(0).isNoPunchYet());
    assertTrue(r.getRows().get(0).isMarkReasonRequired());
  }

  @Test
  void noPunchYetAndMarkReasonNeverShowWithTheSwitchOff() {
    paired("e1");
    String today = HrDateUtils.format(HrDateUtils.today());

    DailyRegisterResponse r = service.daily(USER, SHOP, today);

    assertFalse(r.getRows().get(0).isNoPunchYet());
    assertFalse(r.getRows().get(0).isMarkReasonRequired());
    verify(pairingService, never()).activePairingsByEmployee(any(), anyCollection());
  }

  @Test
  void theMonthRegisterFlagsPairedEmployeesWithOnePairingsRead() {
    biometricAppOn();
    paired("e2");
    when(periodLockService.lockedPeriods(eq(SHOP), anyCollection())).thenReturn(Set.of());

    MonthRegisterResponse m = service.month(USER, SHOP, "2025-04", null);

    assertFalse(m.getRows().get(0).isMarkReasonRequired());
    assertTrue(m.getRows().get(1).isMarkReasonRequired());
    verify(pairingService, times(1)).activePairingsByEmployee(eq(SHOP), anyCollection());
  }

  @Test
  void theMonthRegisterReadsNoPairingsWithTheSwitchOff() {
    paired("e2");

    MonthRegisterResponse m = service.month(USER, SHOP, "2025-04", null);

    assertFalse(m.getRows().get(1).isMarkReasonRequired());
    verify(pairingService, never()).activePairingsByEmployee(any(), anyCollection());
  }
}
