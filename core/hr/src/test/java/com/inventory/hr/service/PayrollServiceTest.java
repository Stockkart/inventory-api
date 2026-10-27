package com.inventory.hr.service;

import com.inventory.accounting.api.JournalLineView;
import com.inventory.accounting.api.JournalEntryView;
import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.JournalSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.HrSettings;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.AttendanceStatus;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayType;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.mapper.HrPayrollMapperImpl;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import com.inventory.hr.service.payroll.PayrollInput;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.PayrollAdjustmentValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PayrollServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private PayrollRunRepository runRepository;
  @Mock private PayrollLineRepository lineRepository;
  @Mock private HrPermissionGuard guard;
  @Mock private PayrollInputReader inputReader;
  @Mock private HrAccountingPoster poster;
  @Mock private HrAuditService auditService;
  @Mock private MetricsWrapper metrics;
  @Mock private SalaryPaymentService salaryPaymentService;
  /** startDay → 0 from the mock = start day 1 (calendar months), the behaviour before. */
  @Mock private PeriodLockService periodLockService;
  @Spy private PayrollAdjustmentValidator validator = new PayrollAdjustmentValidator();
  @Spy private HrPayrollMapper mapper = new HrPayrollMapperImpl();
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private PayrollService service;

  private final BulkOperations bulk = mock(BulkOperations.class);

  private static PayrollRun run(String id, String period, PayrollRunStatus status) {
    PayrollRun r = new PayrollRun();
    r.setId(id);
    r.setShopId(SHOP);
    r.setPeriod(period);
    r.setStatus(status);
    r.setRevision(0);
    return r;
  }

  /** One daily-wage employee employed only on 1 April, marked Present that day. */
  private static PayrollInputReader.Snapshot oneDaySnapshot() {
    Employee e = new Employee();
    e.setId("e1");
    e.setCode("EMP-001");
    e.setJoinDate("2025-04-01");
    e.setExitDate("2025-04-01");
    e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision("2025-01",
        PayType.DAILY, new BigDecimal("100"), Instant.EPOCH, USER))));
    AttendanceDay d = new AttendanceDay();
    d.setEmployeeId("e1");
    d.setDate("2025-04-01");
    d.setStatus(AttendanceStatus.PRESENT);
    HrSettings settings = HrSettingsService.withDefaults(null, SHOP);
    PayrollInput input = new PayrollInput(YearMonth.of(2025, 4), settings, Set.of(), Map.of(),
        List.of(new PayrollInput.EmployeeInput(e, Map.of("2025-04-01", d), Map.of(), List.of(),
            null, List.of())));
    return new PayrollInputReader.Snapshot(input, Map.of());
  }

  @BeforeEach
  void setUp() {
    when(mongoTemplate.bulkOps(any(BulkOperations.BulkMode.class), eq(PayrollLine.class)))
        .thenReturn(bulk);
    when(bulk.upsert(any(Query.class), any(Update.class))).thenReturn(bulk);
    when(bulk.remove(any(Query.class))).thenReturn(bulk);
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(PayrollRun.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(PayrollLine.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    when(lineRepository.findByShopIdAndRunId(eq(SHOP), anyString())).thenReturn(List.of());
  }

  @Test
  void listAcceptsEitherPayrollPermission() {
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("r1", "2025-04", PayrollRunStatus.DRAFT)));
    assertEquals(1, service.list(USER, SHOP).size());
    verify(guard).requireOneOf(USER, SHOP, HrPermission.RUN_PAYROLL,
        HrPermission.RECORD_PAYMENTS);
  }

  @Test
  void createReturnsTheMonthsRunWhenItExists() {
    PayrollRun existing = run("r1", "2025-04", PayrollRunStatus.FINALISED);
    when(runRepository.findByShopIdAndPeriod(SHOP, "2025-04")).thenReturn(Optional.of(existing));

    PayrollRunResponse r = service.create(USER, SHOP, new CreatePayrollRunRequest("2025-04"));

    assertEquals("r1", r.getId());
    verify(guard).require(USER, SHOP, HrPermission.RUN_PAYROLL);
    verify(inputReader, never()).read(anyString(), any(), any(), anyCollection(), any(),
        anyBoolean());
    verify(runRepository, never()).insert(any(PayrollRun.class));
  }

  @Test
  void aFutureMonthIsRefused() {
    String next = YearMonth.now().plusMonths(2).toString();
    assertThrows(ValidationException.class,
        () -> service.create(USER, SHOP, new CreatePayrollRunRequest(next)));
  }

  /** Runs {@code body} with HrDateUtils.today() pinned (PayCycle.currentPeriod reads it). */
  private static void on(java.time.LocalDate day, Runnable body) {
    try (org.mockito.MockedStatic<com.inventory.hr.utils.HrDateUtils> dates =
        org.mockito.Mockito.mockStatic(com.inventory.hr.utils.HrDateUtils.class,
            org.mockito.Mockito.CALLS_REAL_METHODS)) {
      dates.when(com.inventory.hr.utils.HrDateUtils::today).thenReturn(day);
      body.run();
    }
  }

  @Test
  void theStartedSalaryMonthCanBeRunBeforeItsCalendarMonth() {
    // start 24: on 26 Oct the November salary month (24 Oct – 23 Nov) has begun
    when(periodLockService.startDay(SHOP)).thenReturn(24);
    when(runRepository.findByShopIdAndPeriod(SHOP, "2026-11"))
        .thenReturn(Optional.of(run("r1", "2026-11", PayrollRunStatus.DRAFT)));
    on(java.time.LocalDate.of(2026, 10, 26), () -> {
      assertEquals("r1",
          service.create(USER, SHOP, new CreatePayrollRunRequest("2026-11")).getId());
      assertThrows(ValidationException.class,
          () -> service.create(USER, SHOP, new CreatePayrollRunRequest("2026-12")));
    });
  }

  @Test
  void withStartDayOneTheNextCalendarMonthIsStillFuture() {
    when(periodLockService.startDay(SHOP)).thenReturn(1);
    on(java.time.LocalDate.of(2026, 10, 26), () -> assertThrows(ValidationException.class,
        () -> service.create(USER, SHOP, new CreatePayrollRunRequest("2026-11"))));
  }

  @Test
  void onlyADraftCanBeRecalculated() {
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", "2025-04", PayrollRunStatus.FINALISED)));
    BaseException e = assertThrows(BaseException.class,
        () -> service.recalculate(USER, SHOP, "r1"));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
  }

  @Test
  void finaliseRefusesALaterMonthBeforeClaimingAnything() {
    when(runRepository.findByShopIdAndId(SHOP, "may"))
        .thenReturn(Optional.of(run("may", "2025-05", PayrollRunStatus.DRAFT)));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class))).thenReturn(List.of(
        run("apr", "2025-04", PayrollRunStatus.DRAFT), run("may", "2025-05",
            PayrollRunStatus.DRAFT)));

    BaseException e = assertThrows(BaseException.class,
        () -> service.finalise(USER, SHOP, "may"));

    assertEquals(ErrorCode.HR_RUN_ORDER, e.getErrorCode());
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class));
  }

  @Test
  void aFinalisedRunIsReturnedAsIsOnRetry() {
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", "2025-04", PayrollRunStatus.FINALISED)));
    assertEquals(PayrollRunStatus.FINALISED, service.finalise(USER, SHOP, "r1").getStatus());
    verify(poster, never()).postPayrollRun(anyString(), anyString(), any(), anyCollection());
  }

  @Test
  void aFailedJournalPostReleasesTheClaimAndIsCounted() {
    PayrollRun draft = run("r1", "2025-04", PayrollRunStatus.DRAFT);
    PayrollRun claimed = run("r1", "2025-04", PayrollRunStatus.FINALISING);
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(draft));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class))).thenReturn(List.of(draft));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class))).thenReturn(claimed);
    when(inputReader.read(eq(SHOP), eq(YearMonth.of(2025, 4)), eq("r1"), anyList(), isNull(),
        eq(true))).thenReturn(oneDaySnapshot());
    RuntimeException down = new IllegalStateException("accounting down");
    when(poster.postPayrollRun(eq(SHOP), eq(USER), any(PayrollRun.class), anyCollection()))
        .thenThrow(down);
    when(mongoTemplate.exists(any(Query.class), eq(PayrollRun.class))).thenReturn(true);
    when(poster.findRunJournal(eq(SHOP), any(PayrollRun.class))).thenReturn(Optional.empty());

    RuntimeException e = assertThrows(RuntimeException.class,
        () -> service.finalise(USER, SHOP, "r1"));

    assertSame(down, e);
    verify(metrics).increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1, "module",
        HrMetricsConstants.MODULE, "source", JournalSource.PAYROLL_RUN.name());
    // lines unfrozen, then the run back to DRAFT (both only under our claim)
    verify(mongoTemplate).updateMulti(any(Query.class), any(Update.class),
        eq(PayrollLine.class));
    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
        eq(PayrollRun.class));
    verify(bulk).execute();
  }

  /** A finalise set up to reach the journal post: draft run, claim granted, one-day snapshot. */
  private void readyToPost() {
    PayrollRun draft = run("r1", "2025-04", PayrollRunStatus.DRAFT);
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(draft));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class))).thenReturn(List.of(draft));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class)))
        .thenReturn(run("r1", "2025-04", PayrollRunStatus.FINALISING),
            run("r1", "2025-04", PayrollRunStatus.FINALISED));
    when(inputReader.read(eq(SHOP), eq(YearMonth.of(2025, 4)), eq("r1"), anyList(), isNull(),
        eq(true))).thenReturn(oneDaySnapshot());
    when(mongoTemplate.exists(any(Query.class), eq(PayrollRun.class))).thenReturn(true);
  }

  /** The entry the one-day snapshot posts: Dr 5700 100 / Cr 2400 100, or another net. */
  private static JournalEntryView entry(String net) {
    JournalLineView payable = new JournalLineView("2400", null, new BigDecimal(net));
    return new JournalEntryView("je-1", null, null, null, null, new BigDecimal(net),
        new BigDecimal(net), List.of(payable));
  }

  @Test
  void aPostThatSavedItsEntryBeforeFailingFinishesWithThatEntry() {
    readyToPost();
    when(poster.postPayrollRun(eq(SHOP), eq(USER), any(PayrollRun.class), anyCollection()))
        .thenThrow(new IllegalStateException("ledger write failed"));
    when(poster.findRunJournal(eq(SHOP), any(PayrollRun.class)))
        .thenReturn(Optional.of(entry("100")));

    PayrollRunResponse r = service.finalise(USER, SHOP, "r1");

    assertEquals(PayrollRunStatus.FINALISED, r.getStatus());
    // never released to DRAFT under the same revision
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
        eq(PayrollRun.class));
  }

  @Test
  void anEntryPostedFromOtherInputsIsReversedAndTheRunStartsANewRevision() {
    readyToPost();
    when(poster.postPayrollRun(eq(SHOP), eq(USER), any(PayrollRun.class), anyCollection()))
        .thenReturn(entry("90"));

    BaseException e = assertThrows(BaseException.class,
        () -> service.finalise(USER, SHOP, "r1"));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    verify(poster).reverseBySource(eq(SHOP), eq(USER), eq(JournalSource.PAYROLL_RUN),
        eq("r1:0"), anyString());
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(any(Query.class), update.capture(), eq(PayrollRun.class));
    assertEquals(1, update.getValue().getUpdateObject().get("$set", org.bson.Document.class)
        .get("revision"));
  }

  @Test
  void aLostClaimStopsBeforeAnyLineIsWritten() {
    readyToPost();
    when(mongoTemplate.exists(any(Query.class), eq(PayrollRun.class))).thenReturn(false);

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> service.finalise(USER, SHOP, "r1")).getErrorCode());
    verify(bulk, never()).execute();
    verify(poster, never()).postPayrollRun(anyString(), anyString(), any(), anyCollection());
  }

  @Test
  void aReversalThatFailsLeavesTheRunReopeningForTheNextCall() {
    PayrollRun finalised = run("r1", "2025-04", PayrollRunStatus.FINALISED);
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(finalised));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(finalised));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class)))
        .thenReturn(run("r1", "2025-04", PayrollRunStatus.REOPENING));
    when(poster.reverseBySource(anyString(), anyString(), any(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("half done"));

    assertThrows(IllegalStateException.class, () -> service.reopen(USER, SHOP, "r1", "fix"));

    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
        eq(PayrollRun.class));
  }

  @Test
  void reopenWithAPaidLineGoesBackToFinalisedWithoutReversing() {
    PayrollRun finalised = run("r1", "2025-04", PayrollRunStatus.FINALISED);
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(finalised));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(finalised));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class)))
        .thenReturn(run("r1", "2025-04", PayrollRunStatus.REOPENING));
    when(mongoTemplate.exists(any(Query.class), eq(PayrollLine.class))).thenReturn(true);

    BaseException e = assertThrows(BaseException.class,
        () -> service.reopen(USER, SHOP, "r1", "fix"));

    assertEquals(ErrorCode.HR_RUN_HAS_PAYMENTS, e.getErrorCode());
    verify(poster, never()).reverseBySource(anyString(), anyString(), any(), anyString(),
        anyString());
    verify(mongoTemplate).updateFirst(any(Query.class), any(Update.class),
        eq(PayrollRun.class));
  }

  @Test
  void reopenNeedsAReasonAndAFinalisedRun() {
    assertThrows(ValidationException.class, () -> service.reopen(USER, SHOP, "r1", " "));
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", "2025-04", PayrollRunStatus.DRAFT)));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> service.reopen(USER, SHOP, "r1", "fix")).getErrorCode());
  }

  @Test
  void adjustmentsThatPushNetBelowZeroAreRefusedAndNothingIsWritten() {
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", "2025-04", PayrollRunStatus.DRAFT)));
    PayrollLine line = new PayrollLine();
    line.setId("l1");
    line.setRunId("r1");
    line.setEmployeeId("e1");
    when(lineRepository.findByShopIdAndId(SHOP, "l1")).thenReturn(Optional.of(line));
    when(inputReader.read(eq(SHOP), any(), eq("r1"), anyList(), eq(Set.of("e1")), eq(true)))
        .thenReturn(oneDaySnapshot());
    SavePayrollAdjustmentsRequest request = new SavePayrollAdjustmentsRequest(
        List.of(new SavePayrollAdjustmentsRequest.Item("OTHER_DEDUCTION", "loss",
            new BigDecimal("500"))), null);

    assertThrows(ValidationException.class,
        () -> service.saveAdjustments(USER, SHOP, "r1", "l1", request));
    verify(mongoTemplate, never()).updateFirst(any(Query.class), any(Update.class),
        eq(PayrollLine.class));
  }

  @Test
  void theOrderRulesAreOldestFirstToFinaliseAndNewestFirstToReopen() {
    PayrollRun apr = run("apr", "2025-04", PayrollRunStatus.FINALISED);
    PayrollRun may = run("may", "2025-05", PayrollRunStatus.DRAFT);
    PayrollRun jun = run("jun", "2025-06", PayrollRunStatus.FINALISING);
    PayrollService.assertFinaliseOrder(may, List.of(apr, may));
    assertEquals(ErrorCode.HR_RUN_ORDER, assertThrows(BaseException.class,
        () -> PayrollService.assertFinaliseOrder(may, List.of(apr, may, jun))).getErrorCode());
    PayrollService.assertReopenOrder(apr, List.of(apr, may));
    assertEquals(ErrorCode.HR_RUN_ORDER, assertThrows(BaseException.class,
        () -> PayrollService.assertReopenOrder(apr, List.of(apr, may, jun))).getErrorCode());
  }

  @Test
  void aClaimIsStaleAfterTwoMinutes() {
    PayrollRun r = run("r1", "2025-04", PayrollRunStatus.FINALISING);
    Instant now = Instant.parse("2025-05-01T10:00:00Z");
    r.setClaimedAt(now.minusSeconds(119));
    assertEquals(false, PayrollService.isStale(r, now));
    r.setClaimedAt(now.minusSeconds(121));
    assertEquals(true, PayrollService.isStale(r, now));
  }
  @Test
  void theCurrentMonthCannotBeFinalisedBeforeItIsOver() {
    java.time.LocalDate today = com.inventory.hr.utils.HrDateUtils.today();
    java.time.YearMonth current = java.time.YearMonth.from(today);
    org.junit.jupiter.api.Assumptions.assumeTrue(today.isBefore(current.atEndOfMonth()),
        "on the last day of a month nothing of it is in the future");
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", current.toString(), PayrollRunStatus.DRAFT)));

    ValidationException e = assertThrows(ValidationException.class,
        () -> service.finalise(USER, SHOP, "r1"));

    java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter
        .ofPattern("d MMM yyyy", java.util.Locale.ENGLISH);
    assertEquals("This salary month ends on " + f.format(current.atEndOfMonth())
        + ". You can finalise it from " + f.format(current.plusMonths(1).atDay(1)) + ".",
        e.getMessage());
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollRun.class));
  }

  @Test
  void aSalaryMonthStartingOnThe26thCanBeFinalisedFromThe26thOfItsEndMonth() {
    PayCycle.Range oct = PayCycle.range("2026-10", 26);

    ValidationException e = assertThrows(ValidationException.class,
        () -> PayrollService.assertMonthOver(oct, java.time.LocalDate.of(2026, 10, 25)));
    assertEquals("This salary month ends on 25 Oct 2026. You can finalise it from 26 Oct 2026.",
        e.getMessage());
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(
        () -> PayrollService.assertMonthOver(oct, java.time.LocalDate.of(2026, 10, 26)));
    // start day 1 as before: October is finalisable from 1 November
    assertThrows(ValidationException.class, () -> PayrollService.assertMonthOver(
        PayCycle.range("2026-10", 1), java.time.LocalDate.of(2026, 10, 31)));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> PayrollService.assertMonthOver(
        PayCycle.range("2026-10", 1), java.time.LocalDate.of(2026, 11, 1)));
  }

  @Test
  void aMonthAfterThisOneCannotBeFinalisedEither() {
    String next = java.time.YearMonth.from(com.inventory.hr.utils.HrDateUtils.today())
        .plusMonths(1).toString();
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", next, PayrollRunStatus.DRAFT)));

    ValidationException e = assertThrows(ValidationException.class,
        () -> service.finalise(USER, SHOP, "r1"));
    assertEquals(true, e.getMessage().startsWith("This salary month ends on"));
  }

  /** One employee, no marks, employed all of {@code month}. */
  private static PayrollInputReader.Snapshot unmarkedSnapshot(YearMonth month) {
    Employee e = new Employee();
    e.setId("e1");
    e.setCode("EMP-001");
    e.setJoinDate("2025-01-01");
    e.setSalaryRevisions(new ArrayList<>(List.of(new Employee.SalaryRevision("2025-01",
        PayType.DAILY, new BigDecimal("100"), Instant.EPOCH, USER))));
    HrSettings settings = HrSettingsService.withDefaults(null, SHOP);
    PayrollInput input = new PayrollInput(month, settings, Set.of(), Map.of(),
        List.of(new PayrollInput.EmployeeInput(e, Map.of(), Map.of(), List.of(), null,
            List.of())));
    return new PayrollInputReader.Snapshot(input, Map.of());
  }

  @Test
  void theUnmarkedListLeavesOutDaysAfterToday() {
    YearMonth next = YearMonth.from(com.inventory.hr.utils.HrDateUtils.today()).plusMonths(1);
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", next.toString(), PayrollRunStatus.DRAFT)));
    when(inputReader.read(eq(SHOP), eq(next), isNull(), anyList(), isNull(), eq(false)))
        .thenReturn(unmarkedSnapshot(next));

    assertEquals(List.of(), service.unmarked(USER, SHOP, "r1").getEmployees());
  }

  @Test
  void theUnmarkedListStillHasPastUnmarkedDays() {
    YearMonth april = YearMonth.of(2025, 4);
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run("r1", "2025-04", PayrollRunStatus.DRAFT)));
    when(inputReader.read(eq(SHOP), eq(april), isNull(), anyList(), isNull(), eq(false)))
        .thenReturn(unmarkedSnapshot(april));

    var rows = service.unmarked(USER, SHOP, "r1").getEmployees();

    assertEquals(1, rows.size());
    // April 2025: 30 days − 4 Sundays
    assertEquals(26, rows.get(0).getDates().size());
  }
}
