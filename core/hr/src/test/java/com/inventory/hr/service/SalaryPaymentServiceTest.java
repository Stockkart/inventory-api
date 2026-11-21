package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse;
import com.inventory.hr.validation.SalaryPaymentValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.client.result.UpdateResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Pay / undo branches (task 9.1); the Mongo paths themselves are in SalaryPaymentIT. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalaryPaymentServiceTest {

  private static final String SHOP = "s1";
  private static final String USER = "u1";

  @Mock private MongoTemplate mongoTemplate;
  @Mock private PayrollRunRepository runRepository;
  @Mock private PayrollLineRepository lineRepository;
  @Mock private HrPermissionGuard guard;
  @Mock private HrAccountingPoster poster;
  @Mock private HrAuditService auditService;
  @Mock private HrPayrollMapper mapper;
  @Mock private MetricsWrapper metrics;
  @Spy private SalaryPaymentValidator validator = new SalaryPaymentValidator();
  @Spy private HrQueryRunner queryRunner = new HrQueryRunner();
  @InjectMocks private SalaryPaymentService service;

  @BeforeEach
  void setUp() {
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(PayrollLine.class)))
        .thenReturn(UpdateResult.acknowledged(1, 1L, null));
    when(mongoTemplate.find(any(Query.class), eq(PayrollLine.class))).thenReturn(List.of());
  }

  private static PayrollRun run(PayrollRunStatus status) {
    PayrollRun r = new PayrollRun();
    r.setId("r1");
    r.setShopId(SHOP);
    r.setPeriod("2025-04");
    r.setStatus(status);
    return r;
  }

  private static RecordSalaryPaymentsRequest pay(String... ids) {
    RecordSalaryPaymentsRequest r = new RecordSalaryPaymentsRequest();
    r.setLineIds(new ArrayList<>(List.of(ids)));
    r.setDate("2025-05-02");
    r.setMethod("CASH");
    return r;
  }

  /** Every find of lines returns the given lines (stale scan, then the requested read). */
  private void linesRead(List<PayrollLine> stale, List<PayrollLine> requested) {
    when(mongoTemplate.find(any(Query.class), eq(PayrollLine.class)))
        .thenReturn(stale).thenReturn(requested);
  }

  @Test
  void needsRecordPaymentsAndAFinalisedRun() {
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(run(
        PayrollRunStatus.DRAFT)));

    BaseException e = assertThrows(BaseException.class,
        () -> service.pay(USER, SHOP, "r1", pay("l1")));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    verify(guard).require(USER, SHOP, HrPermission.RECORD_PAYMENTS);
    verify(poster, never()).postPaymentBatch(anyString(), anyString(), anyString(),
        anyCollection(), any(), any());
  }

  @Test
  void anUnknownRunIs404() {
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.empty());

    assertThrows(ResourceNotFoundException.class, () -> service.pay(USER, SHOP, "r1", pay("l1")));
  }

  @Test
  void linesNobodyClaimedAreReportedAndNothingIsPosted() {
    when(runRepository.findByShopIdAndId(SHOP, "r1")).thenReturn(Optional.of(run(
        PayrollRunStatus.FINALISED)));
    PayrollLine paid = new PayrollLine();
    paid.setId("l1");
    paid.setPaymentStatus(SalaryPaymentStatus.PAID);
    paid.setPaymentBatchId("old-batch");
    linesRead(List.of(), List.of(paid));

    SalaryPaymentsResponse r = service.pay(USER, SHOP, "r1", pay("l1", "l2"));

    assertEquals(0, r.getPaid());
    assertEquals(SalaryPaymentsResponse.LineStatus.ALREADY_PAID, r.getLines().get(0).getStatus());
    assertEquals(SalaryPaymentsResponse.LineStatus.NOT_FOUND, r.getLines().get(1).getStatus());
    verify(poster, never()).postPaymentBatch(anyString(), anyString(), anyString(),
        anyCollection(), any(), any());
  }

  @Test
  void aRunReopenedAfterTheClaimReleasesTheLinesAndRefuses() {
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run(PayrollRunStatus.FINALISED)))
        .thenReturn(Optional.of(run(PayrollRunStatus.REOPENING)));
    List<Update> updates = new ArrayList<>();
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(PayrollLine.class)))
        .thenAnswer(inv -> {
          updates.add(inv.getArgument(1));
          return UpdateResult.acknowledged(1, 1L, null);
        });
    // the requested read returns the line under the batch id of the claim
    when(mongoTemplate.find(any(Query.class), eq(PayrollLine.class))).thenAnswer(inv -> {
      Query q = inv.getArgument(0);
      if (!q.getQueryObject().containsKey("_id")) {
        return List.of(); // stale scan
      }
      PayrollLine l = new PayrollLine();
      l.setId("l1");
      l.setNet(new BigDecimal("100"));
      l.setPaymentStatus(SalaryPaymentStatus.PAYING);
      l.setPaymentBatchId(updates.get(0).getUpdateObject()
          .get("$set", org.bson.Document.class).getString("paymentBatchId"));
      return List.of(l);
    });

    BaseException e = assertThrows(BaseException.class,
        () -> service.pay(USER, SHOP, "r1", pay("l1")));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    assertEquals(2, updates.size(), "claim, then release");
    assertEquals("PENDING", updates.get(1).getUpdateObject()
        .get("$set", org.bson.Document.class).get("paymentStatus").toString());
    verify(poster, never()).postPaymentBatch(anyString(), anyString(), anyString(),
        anyCollection(), any(), any());
  }

  @Test
  void aSlowCallWhoseLinesWereReleasedReversesItsJournalAndRefuses() {
    when(runRepository.findByShopIdAndId(SHOP, "r1"))
        .thenReturn(Optional.of(run(PayrollRunStatus.FINALISED)));
    List<Update> updates = new ArrayList<>();
    when(mongoTemplate.updateMulti(any(Query.class), any(Update.class), eq(PayrollLine.class)))
        .thenAnswer(inv -> {
          updates.add(inv.getArgument(1));
          // the claim matches; the PAID update finds nothing: a take-over released the lines
          return UpdateResult.acknowledged(1, updates.size() == 1 ? 1L : 0L, null);
        });
    when(mongoTemplate.find(any(Query.class), eq(PayrollLine.class))).thenAnswer(inv -> {
      Query q = inv.getArgument(0);
      if (!q.getQueryObject().containsKey("_id")) {
        return List.of();
      }
      PayrollLine l = new PayrollLine();
      l.setId("l1");
      l.setNet(new BigDecimal("100"));
      l.setPaymentStatus(SalaryPaymentStatus.PAYING);
      l.setPaymentBatchId(updates.get(0).getUpdateObject()
          .get("$set", org.bson.Document.class).getString("paymentBatchId"));
      return List.of(l);
    });
    com.inventory.accounting.api.JournalEntryView entry =
        new com.inventory.accounting.api.JournalEntryView("j1", null, null, null, null, null, null,
            java.util.List.of());
    when(poster.postPaymentBatch(anyString(), anyString(), anyString(), anyCollection(), any(),
        any())).thenReturn(entry);
    when(mongoTemplate.exists(any(Query.class), eq(PayrollLine.class))).thenReturn(false);

    BaseException e = assertThrows(BaseException.class,
        () -> service.pay(USER, SHOP, "r1", pay("l1")));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    verify(poster).reverseBySource(eq(SHOP), eq(USER),
        eq(com.inventory.accounting.api.JournalSource.SALARY_PAYMENT), anyString(),
        anyString());
    verify(auditService, never()).recordAll(any());
  }

  @Test
  void undoOfALineThatIsNotPaidIsAConflict() {
    PayrollLine pending = new PayrollLine();
    pending.setId("l1");
    pending.setRunId("r1");
    pending.setPaymentStatus(SalaryPaymentStatus.PENDING);
    when(lineRepository.findByShopIdAndId(SHOP, "l1")).thenReturn(Optional.of(pending));

    BaseException e = assertThrows(BaseException.class,
        () -> service.undo(USER, SHOP, "r1", "l1", null));

    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, e.getErrorCode());
    verify(mongoTemplate, never()).findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollLine.class));
  }

  @Test
  void aFailedUndoPostPutsTheLineBackToPaid() {
    PayrollLine paidLine = new PayrollLine();
    paidLine.setId("l1");
    paidLine.setRunId("r1");
    paidLine.setPaymentSeq(1);
    paidLine.setPaymentStatus(SalaryPaymentStatus.PAID);
    when(lineRepository.findByShopIdAndId(SHOP, "l1")).thenReturn(Optional.of(paidLine));
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
        any(FindAndModifyOptions.class), eq(PayrollLine.class))).thenReturn(paidLine);
    RuntimeException boom = new IllegalStateException("ledger down");
    when(poster.postPaymentUndo(anyString(), anyString(), any(), eq(1), any(), any()))
        .thenThrow(boom);
    when(poster.findPaymentJournal(SHOP, "undo:l1:1")).thenReturn(Optional.empty());

    RuntimeException thrown = assertThrows(RuntimeException.class,
        () -> service.undo(USER, SHOP, "r1", "l1", null));

    assertSame(boom, thrown);
    ArgumentCaptor<Update> back = ArgumentCaptor.forClass(Update.class);
    verify(mongoTemplate).updateFirst(any(Query.class), back.capture(), eq(PayrollLine.class));
    assertEquals("PAID", back.getValue().getUpdateObject().get("$set", org.bson.Document.class)
        .get("paymentStatus").toString());
  }
}
