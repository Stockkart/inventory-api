package com.inventory.hr.service;

import com.inventory.accounting.api.JournalEntryView;
import static com.inventory.hr.HrTestFixtures.SHOP;
import static com.inventory.hr.HrTestFixtures.USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.accounting.api.JournalSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.repository.AdvanceRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.mapper.HrPayrollMapperImpl;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.response.AdvanceResponse;
import com.inventory.hr.rest.dto.response.AdvanceStatementResponse;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.AdvanceValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdvanceServiceTest {

  @Mock private MongoTemplate mongoTemplate;
  @Mock private AdvanceRepository advanceRepository;
  @Mock private HrPermissionGuard guard;
  @Mock private HrAccountingPoster poster;
  @Mock private HrAuditService auditService;
  @Mock private MetricsWrapper metrics;
  @Spy private AdvanceValidator validator = new AdvanceValidator();
  @Spy private HrPayrollMapper mapper = new HrPayrollMapperImpl();
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private AdvanceService service;

  private static Employee employee() {
    Employee e = new Employee();
    e.setId("e1");
    e.setCode("EMP-001");
    e.setName("Ravi");
    e.setJoinDate("2024-01-01");
    return e;
  }

  private static Advance advance(String id, AdvanceStatus status, String journalId) {
    Advance a = new Advance();
    a.setId(id);
    a.setShopId(SHOP);
    a.setEmployeeId("e1");
    a.setDate("2025-03-01");
    a.setAmount(new BigDecimal("3000"));
    a.setMonthlyDeduction(new BigDecimal("1000"));
    a.setPaidBy(HrPaymentMethod.CASH);
    a.setStatus(status);
    a.setJournalEntryId(journalId);
    return a;
  }

  private static PayrollRun run(String id, PayrollRunStatus status) {
    PayrollRun r = new PayrollRun();
    r.setId(id);
    r.setPeriod("2025-04");
    r.setStatus(status);
    return r;
  }

  private void recovered(String advanceId, String amount) {
    List<Document> rows = amount == null ? List.of()
        : List.of(new Document("_id", advanceId).append("recovered", new BigDecimal(amount)));
    when(mongoTemplate.aggregate(any(Aggregation.class), eq(PayrollLine.class),
        eq(Document.class))).thenReturn(new AggregationResults<>(rows, new Document()));
  }

  @BeforeEach
  void setUp() {
    when(mongoTemplate.findOne(any(Query.class), eq(Employee.class))).thenReturn(employee());
    when(mongoTemplate.find(any(Query.class), eq(Employee.class))).thenReturn(List.of(employee()));
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Advance.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    when(advanceRepository.insert(any(Advance.class))).thenAnswer(i -> {
      Advance a = i.getArgument(0);
      a.setId("new");
      return a;
    });
    JournalEntryView entry = new JournalEntryView("je-1", null, null, null, null, null, null,
        List.of());
    when(poster.postAdvance(eq(SHOP), eq(USER), any(Advance.class), eq("EMP-001")))
        .thenReturn(entry);
  }

  private static RecordAdvanceRequest request() {
    return new RecordAdvanceRequest("e1", "2025-03-01", new BigDecimal("3000"),
        new BigDecimal("1000"), "CASH", " shop rent ");
  }

  /** The pending-journal read sees the advance just inserted. */
  private void pendingIsTheNewAdvance() {
    when(mongoTemplate.find(any(Query.class), eq(Advance.class)))
        .thenAnswer(i -> new ArrayList<>(List.of(advance("new", AdvanceStatus.ACTIVE, null))));
  }

  @Test
  void recordChecksThePermissionInsertsThenPosts() {
    pendingIsTheNewAdvance();

    AdvanceResponse r = service.record(USER, SHOP, request());

    verify(guard).require(USER, SHOP, HrPermission.MANAGE_ADVANCES);
    assertTrue(r.isJournalPosted());
    assertEquals(0, new BigDecimal("3000").compareTo(r.getOutstanding()));
    verify(poster).postAdvance(eq(SHOP), eq(USER), any(Advance.class), eq("EMP-001"));
  }

  @Test
  void aShopsOwnStaffAdvancesAccountStopsTheAdvanceBeforeItIsSaved() {
    org.mockito.Mockito.doThrow(new com.inventory.common.exception.BaseException(
        com.inventory.common.constants.ErrorCode.HR_ACCOUNT_CONFLICT, "Account 1250 ..."))
        .when(poster).assertHrAccounts(SHOP, List.of("1250"));

    com.inventory.common.exception.BaseException e = org.junit.jupiter.api.Assertions
        .assertThrows(com.inventory.common.exception.BaseException.class,
            () -> service.record(USER, SHOP, request()));

    assertEquals(com.inventory.common.constants.ErrorCode.HR_ACCOUNT_CONFLICT, e.getErrorCode());
    verify(advanceRepository, never()).insert(any(Advance.class));
  }

  @Test
  void aFailedPostKeepsTheAdvancePendingAndCountsTheFailure() {
    pendingIsTheNewAdvance();
    when(poster.postAdvance(eq(SHOP), eq(USER), any(Advance.class), anyString()))
        .thenThrow(new IllegalStateException("accounting down"));

    AdvanceResponse r = service.record(USER, SHOP, request());

    assertFalse(r.isJournalPosted());
    assertEquals("shop rent", r.getNote());
    verify(metrics).increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1, "module",
        HrMetricsConstants.MODULE, "source", JournalSource.STAFF_ADVANCE.name());
  }

  @Test
  void anAdvanceCancelledWhileItWasBeingPostedHasItsEntryReversed() {
    pendingIsTheNewAdvance();
    // the journal id update finds the advance no longer ACTIVE: a cancel got in between
    when(mongoTemplate.updateFirst(any(Query.class), any(Update.class), eq(Advance.class)))
        .thenReturn(UpdateResult.acknowledged(0, 0L, null));
    when(mongoTemplate.exists(any(Query.class), eq(Advance.class))).thenReturn(true);

    service.record(USER, SHOP, request());

    verify(poster).reverseBySource(SHOP, USER, JournalSource.STAFF_ADVANCE, "new",
        "Advance cancelled");
  }

  @Test
  void anAdvanceBeforeTheJoinDateIsRefused() {
    Employee e = employee();
    e.setJoinDate("2025-04-01");
    when(mongoTemplate.findOne(any(Query.class), eq(Employee.class))).thenReturn(e);
    BaseException ex = assertThrows(BaseException.class,
        () -> service.record(USER, SHOP, request()));
    assertEquals(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED, ex.getErrorCode());
    verify(advanceRepository, never()).insert(any(Advance.class));
  }

  @Test
  void invalidInputNeverReachesTheDatabase() {
    RecordAdvanceRequest bad = request();
    bad.setMonthlyDeduction(new BigDecimal("5000"));
    assertThrows(ValidationException.class, () -> service.record(USER, SHOP, bad));
    verify(advanceRepository, never()).insert(any(Advance.class));
  }

  @Test
  void listRepostsAMissingJournalAndDerivesOutstanding() {
    Advance pending = advance("a1", AdvanceStatus.ACTIVE, null);
    when(mongoTemplate.find(any(Query.class), eq(Advance.class))).thenReturn(List.of(pending));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("r1", PayrollRunStatus.FINALISED)));
    recovered("a1", "1000");

    List<AdvanceResponse> rows = service.list(USER, SHOP, "e1");

    assertTrue(rows.get(0).isJournalPosted());
    assertEquals(0, new BigDecimal("2000").compareTo(rows.get(0).getOutstanding()));
    assertEquals(0, new BigDecimal("1000").compareTo(rows.get(0).getRecovered()));
    verify(poster, times(1)).postAdvance(eq(SHOP), eq(USER), any(Advance.class), eq("EMP-001"));
  }

  @Test
  void cancelIsRefusedOnceSomethingWasRecoveredAndTheClaimIsGivenBack() {
    when(advanceRepository.findByShopIdAndId(SHOP, "a1"))
        .thenReturn(Optional.of(advance("a1", AdvanceStatus.ACTIVE, "je-1")));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("r1", PayrollRunStatus.FINALISED)));
    recovered("a1", "1000");

    BaseException e = assertThrows(BaseException.class,
        () -> service.cancel(USER, SHOP, "a1", "mistake"));

    assertEquals(ErrorCode.HR_ADVANCE_HAS_RECOVERIES, e.getErrorCode());
    // claim, then give it back
    verify(mongoTemplate, times(2)).updateFirst(any(Query.class), any(Update.class),
        eq(Advance.class));
    verify(poster, never()).reverseBySource(anyString(), anyString(), any(), anyString(),
        anyString());
  }

  @Test
  void cancelWaitsWhilePayrollIsBeingFinalised() {
    when(advanceRepository.findByShopIdAndId(SHOP, "a1"))
        .thenReturn(Optional.of(advance("a1", AdvanceStatus.ACTIVE, "je-1")));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("r1", PayrollRunStatus.FINALISING)));

    BaseException e = assertThrows(BaseException.class,
        () -> service.cancel(USER, SHOP, "a1", "mistake"));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
  }

  @Test
  void cancelReversesTheJournal() {
    when(advanceRepository.findByShopIdAndId(SHOP, "a1"))
        .thenReturn(Optional.of(advance("a1", AdvanceStatus.ACTIVE, "je-1")));
    when(mongoTemplate.find(any(Query.class), eq(PayrollRun.class)))
        .thenReturn(List.of(run("r1", PayrollRunStatus.DRAFT)));

    AdvanceResponse r = service.cancel(USER, SHOP, "a1", "returned");

    assertEquals(AdvanceStatus.CANCELLED, r.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(r.getOutstanding()));
    verify(poster).reverseBySource(SHOP, USER, JournalSource.STAFF_ADVANCE, "a1",
        "Advance cancelled");
  }

  @Test
  void theStatementRunsTheBalanceOldestFirstAndIgnoresCancelledAdvances() {
    Advance a = advance("a1", AdvanceStatus.ACTIVE, "je-1");
    Advance cancelled = advance("a2", AdvanceStatus.CANCELLED, "je-2");
    cancelled.setDate("2025-02-01");
    PayrollLine april = new PayrollLine();
    april.setPeriod("2025-04");
    april.setAdvanceRecoveries(List.of(new PayrollLine.AdvanceRecovery("a1",
        new BigDecimal("1000"))));

    AdvanceStatementResponse s = AdvanceService.buildStatement(employee(), List.of(a, cancelled),
        List.of(april));

    assertEquals(3, s.getRows().size());
    assertTrue(s.getRows().get(0).isCancelled());
    assertEquals(0, BigDecimal.ZERO.compareTo(s.getRows().get(0).getBalance()));
    assertEquals(0, new BigDecimal("3000").compareTo(s.getRows().get(1).getBalance()));
    assertEquals("2025-04-30", s.getRows().get(2).getDate());
    assertEquals(0, new BigDecimal("2000").compareTo(s.getOutstanding()));
  }
}
