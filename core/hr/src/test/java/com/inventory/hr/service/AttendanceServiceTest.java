package com.inventory.hr.service;

import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.Holiday;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.DayStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.rest.dto.request.MarkUnmarkedPresentRequest;
import com.inventory.hr.rest.dto.request.SaveAttendanceDaysRequest;
import com.inventory.hr.rest.dto.response.AttendanceRowResponse;
import com.inventory.hr.rest.dto.response.DailyRegisterResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.validation.AttendanceDayValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.bulk.BulkWriteUpsert;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
}
