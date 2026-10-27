package com.inventory.hr.service;

import com.inventory.notifications.api.AttachmentRef;
import com.inventory.notifications.api.Attachment;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.api.JournalSource;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.EmailPayslipsRequest;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import com.inventory.hr.rest.dto.response.EmailPayslipsResponse;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse;
import com.inventory.notifications.domain.model.OutboundMessage;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Salary payments, payslips and registers against a real MongoDB and the real accounting module
 * (R9, R10, R12.2, decision 6, Property 6): one SALARY_PAYMENT journal per pay call, one
 * compensating journal per undo, 2400 always nets to the unpaid lines' net, retries and crashed
 * claims post nothing twice, and reopen stays blocked while anything is paid. Each test has its
 * own shop.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class SalaryPaymentIT {

  static final String OWNER = "owner-1";
  static final String APRIL = "2025-04";
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("payments");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  /** A finalised April with {@code n} employees on ₹30,000, ₹31,000, … */
  static List<PayrollLine> finalisedApril(HrMongoItSupport it, String shop, int n) {
    List<Employee> staff = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      staff.add(it.paidEmployee(shop, String.format("EMP-%03d", i + 1), "Staff " + (i + 1),
          "2025-01-01", "2025-01", String.valueOf(30000 + 1000 * i)));
    }
    PayrollRun run = it.finalisedRun(shop, APRIL, staff.toArray(Employee[]::new));
    return lines(it, shop, run.getId());
  }

  static List<PayrollLine> lines(HrMongoItSupport it, String shop, String runId) {
    return it.template.find(Query.query(Criteria.where("shopId").is(shop).and("runId").is(runId)),
        PayrollLine.class);
  }

  static RecordSalaryPaymentsRequest payRequest(List<String> ids, boolean all) {
    RecordSalaryPaymentsRequest r = new RecordSalaryPaymentsRequest();
    r.setLineIds(new ArrayList<>(ids));
    r.setAll(all ? Boolean.TRUE : null);
    r.setDate("2025-05-02");
    r.setMethod("BANK");
    r.setReference("UTR123");
    return r;
  }

  /** Property 6: 2400 = Σ net of lines that are not PAID; one live journal per batch / undo. */
  static void assertBooksMatch(HrMongoItSupport it, String shop, String runId) {
    List<PayrollLine> lines = lines(it, shop, runId);
    BigDecimal unpaid = lines.stream()
        .filter(l -> l.getPaymentStatus() != SalaryPaymentStatus.PAID)
        .map(PayrollLine::getNet).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, unpaid.compareTo(it.salaryPayable(shop)), "2400 nets to the unpaid net");
    Set<String> sources = new HashSet<>();
    for (JournalEntry e : it.liveJournals(shop, JournalSource.SALARY_PAYMENT)) {
      assertTrue(sources.add(e.getSourceId()), "one live journal per batch or undo");
      assertEquals(0, e.getTotalDebit().compareTo(e.getTotalCredit()));
    }
    for (PayrollLine l : lines) {
      if (l.getPaymentStatus() == SalaryPaymentStatus.PAID && l.getNet().signum() > 0) {
        assertTrue(sources.contains(l.getPaymentBatchId()), "a paid line has its batch journal");
      }
    }
  }

  @Test
  void onePayCallIsOneJournalAndARetryPaysNobodyTwice() {
    String shop = "shop-pay";
    List<PayrollLine> lines = finalisedApril(it, shop, 3);
    String runId = lines.get(0).getRunId();
    List<String> two = List.of(lines.get(0).getId(), lines.get(1).getId());

    SalaryPaymentsResponse paid = it.payments.pay(OWNER, shop, runId, payRequest(two, false));
    SalaryPaymentsResponse again = it.payments.pay(OWNER, shop, runId, payRequest(two, false));

    assertEquals(2, paid.getPaid());
    assertNotNull(paid.getJournalEntryId());
    assertEquals(0, again.getPaid());
    assertTrue(again.getLines().stream()
        .allMatch(l -> l.getStatus() == SalaryPaymentsResponse.LineStatus.ALREADY_PAID));
    List<JournalEntry> journals = it.liveJournals(shop, JournalSource.SALARY_PAYMENT);
    assertEquals(1, journals.size());
    assertEquals(paid.getPaymentBatchId(), journals.get(0).getSourceId());
    assertEquals("1110", journals.get(0).getLines().stream()
        .filter(l -> l.getCredit() != null && l.getCredit().signum() > 0).findFirst()
        .orElseThrow().getAccountCode(), "bank payments credit Bank");
    PayrollLine stored = it.template.findById(two.get(0), PayrollLine.class);
    assertEquals(SalaryPaymentStatus.PAID, stored.getPaymentStatus());
    assertEquals(HrPaymentMethod.BANK, stored.getPaymentMethod());
    assertEquals("2025-05-02", stored.getPaidAt());
    assertEquals(paid.getPaymentBatchId(), stored.getPaymentBatchId());
    assertNull(stored.getPaymentClaimedAt());
    assertBooksMatch(it, shop, runId);

    // all:true pays the rest, as a second batch
    SalaryPaymentsResponse rest = it.payments.pay(OWNER, shop, runId, payRequest(List.of(), true));
    assertEquals(1, rest.getPaid());
    assertEquals(2, it.liveJournals(shop, JournalSource.SALARY_PAYMENT).size());
    assertEquals(0, it.salaryPayable(shop).signum());
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void undoPostsACompensatingEntryForThatLineOnlyAndItCanBePaidAgain() {
    String shop = "shop-undo";
    List<PayrollLine> lines = finalisedApril(it, shop, 2);
    String runId = lines.get(0).getRunId();
    List<String> ids = lines.stream().map(PayrollLine::getId).toList();
    it.payments.pay(OWNER, shop, runId, payRequest(ids, false));

    var undone = it.payments.undo(OWNER, shop, runId, ids.get(0), "wrong person");

    assertEquals(SalaryPaymentStatus.PENDING, undone.getPaymentStatus());
    assertNull(undone.getPaidAt());
    PayrollLine line = it.template.findById(ids.get(0), PayrollLine.class);
    assertNull(line.getPaymentBatchId());
    assertEquals(1, line.getPaymentSeq());
    JournalEntry comp = it.accounting.findEntryBySource(shop, JournalSource.SALARY_PAYMENT,
        HrAccountingPoster.undoSourceId(ids.get(0), 1)).orElseThrow();
    assertEquals(0, comp.getTotalDebit().compareTo(line.getNet()), "only that line's net");
    assertEquals(2, it.liveJournals(shop, JournalSource.SALARY_PAYMENT).size(),
        "the batch journal stays; the undo is a new entry, not a reversal");
    assertBooksMatch(it, shop, runId);

    BaseException notPaid = assertThrows(BaseException.class,
        () -> it.payments.undo(OWNER, shop, runId, ids.get(0), null));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, notPaid.getErrorCode());

    it.payments.pay(OWNER, shop, runId, payRequest(List.of(ids.get(0)), false));
    assertEquals(2, it.template.findById(ids.get(0), PayrollLine.class).getPaymentSeq());
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void aCrashedPayIsFinishedByTheNextCallWithItsOwnBatch() {
    String shop = "shop-crash";
    List<PayrollLine> lines = finalisedApril(it, shop, 3);
    String runId = lines.get(0).getRunId();
    // a call that claimed two lines and died before posting, three minutes ago
    String deadBatch = new ObjectId().toHexString();
    it.template.updateMulti(Query.query(Criteria.where("_id").in(lines.get(0).getId(),
        lines.get(1).getId())), new Update().set("paymentStatus", SalaryPaymentStatus.PAYING)
        .inc("paymentSeq", 1).set("paymentBatchId", deadBatch)
        .set("paymentClaimedAt", Instant.now().minusSeconds(180)).set("paidAt", "2025-05-01")
        .set("paymentMethod", HrPaymentMethod.CASH).set("paidByUserId", OWNER), PayrollLine.class);
    // and one that claimed, posted, then died before marking PAID
    String postedBatch = new ObjectId().toHexString();
    it.template.updateFirst(Query.query(Criteria.where("_id").is(lines.get(2).getId())),
        new Update().set("paymentStatus", SalaryPaymentStatus.PAYING).inc("paymentSeq", 1)
            .set("paymentBatchId", postedBatch)
            .set("paymentClaimedAt", Instant.now().minusSeconds(180)).set("paidAt", "2025-05-01")
            .set("paymentMethod", HrPaymentMethod.CASH).set("paidByUserId", OWNER),
        PayrollLine.class);
    it.poster.postPaymentBatch(shop, OWNER, postedBatch,
        List.of(it.template.findById(lines.get(2).getId(), PayrollLine.class)),
        HrPaymentMethod.CASH, java.time.LocalDate.of(2025, 5, 1));

    SalaryPaymentsResponse next = it.payments.pay(OWNER, shop, runId,
        payRequest(List.of(), true));

    // the dead batch had posted nothing, so its lines were released and this call paid them as
    // its own batch; the posted batch was marked PAID with the journal it already had
    assertEquals(2, next.getPaid());
    for (PayrollLine l : lines(it, shop, runId)) {
      assertEquals(SalaryPaymentStatus.PAID, l.getPaymentStatus());
      assertTrue(!deadBatch.equals(l.getPaymentBatchId()), "the dead batch is never recorded");
    }
    List<JournalEntry> journals = it.liveJournals(shop, JournalSource.SALARY_PAYMENT);
    assertEquals(2, journals.size(), "the posted batch once, the new batch once");
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void reopenSettlesAStaleClaimInsteadOfStayingBlocked() {
    String shop = "shop-crash-reopen";
    List<PayrollLine> lines = finalisedApril(it, shop, 1);
    String runId = lines.get(0).getRunId();
    // a pay call that claimed the line and died before posting, three minutes ago
    it.template.updateFirst(Query.query(Criteria.where("_id").is(lines.get(0).getId())),
        new Update().set("paymentStatus", SalaryPaymentStatus.PAYING).inc("paymentSeq", 1)
            .set("paymentBatchId", new ObjectId().toHexString())
            .set("paymentClaimedAt", Instant.now().minusSeconds(180)).set("paidAt", "2025-05-01")
            .set("paymentMethod", HrPaymentMethod.CASH), PayrollLine.class);

    it.payroll.reopen(OWNER, shop, runId, "wrong month");

    assertEquals(SalaryPaymentStatus.PENDING,
        it.template.findById(lines.get(0).getId(), PayrollLine.class).getPaymentStatus());
    assertEquals(0, it.liveJournals(shop, JournalSource.SALARY_PAYMENT).size());
    assertEquals(0, it.salaryPayable(shop).signum());
  }

  @Test
  void aCrashedUndoThatPostedNothingLeavesTheLinePaid() {
    String shop = "shop-crash-undo-none";
    List<PayrollLine> lines = finalisedApril(it, shop, 1);
    String runId = lines.get(0).getRunId();
    String id = lines.get(0).getId();
    it.payments.pay(OWNER, shop, runId, payRequest(List.of(id), false));
    it.template.updateFirst(Query.query(Criteria.where("_id").is(id)),
        new Update().set("paymentStatus", SalaryPaymentStatus.UNDOING)
            .set("paymentClaimedAt", Instant.now().minusSeconds(180)), PayrollLine.class);

    SalaryPaymentsResponse next = it.payments.pay(OWNER, shop, runId, payRequest(List.of(id),
        false));

    assertEquals(SalaryPaymentsResponse.LineStatus.ALREADY_PAID,
        next.getLines().get(0).getStatus());
    assertNull(it.template.findById(id, PayrollLine.class).getPaymentClaimedAt());
    assertEquals(1, it.liveJournals(shop, JournalSource.SALARY_PAYMENT).size());
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void aCrashedUndoIsFinishedWithTheSamePaymentSeq() {
    String shop = "shop-crash-undo";
    List<PayrollLine> lines = finalisedApril(it, shop, 1);
    String runId = lines.get(0).getRunId();
    String id = lines.get(0).getId();
    it.payments.pay(OWNER, shop, runId, payRequest(List.of(id), false));
    // an undo that claimed the line, posted its entry and died
    it.template.updateFirst(Query.query(Criteria.where("_id").is(id)),
        new Update().set("paymentStatus", SalaryPaymentStatus.UNDOING)
            .set("paymentClaimedAt", Instant.now().minusSeconds(180)), PayrollLine.class);
    PayrollLine undoing = it.template.findById(id, PayrollLine.class);
    it.poster.postPaymentUndo(shop, OWNER, undoing, 1, undoing.getPaymentMethod(),
        java.time.LocalDate.of(2025, 5, 3));

    SalaryPaymentsResponse next = it.payments.pay(OWNER, shop, runId, payRequest(List.of(id),
        false));

    assertEquals(1, next.getPaid(), "the finished undo left the line PENDING, so it is paid");
    assertEquals(3, it.liveJournals(shop, JournalSource.SALARY_PAYMENT).size());
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void twoRacingPayCallsPayEachLineOnce() throws Exception {
    String shop = "shop-race";
    List<PayrollLine> lines = finalisedApril(it, shop, 4);
    String runId = lines.get(0).getRunId();
    List<String> ids = lines.stream().map(PayrollLine::getId).toList();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<SalaryPaymentsResponse>> calls = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        calls.add(pool.submit(() -> {
          start.await();
          return it.payments.pay(OWNER, shop, runId, payRequest(ids, false));
        }));
      }
      start.countDown();
      int paid = 0;
      for (Future<SalaryPaymentsResponse> f : calls) {
        paid += f.get().getPaid();
      }
      assertEquals(4, paid);
    } finally {
      pool.shutdownNow();
    }
    assertBooksMatch(it, shop, runId);
  }

  @Test
  void payingNeedsAFinalisedRunAndPaymentsBlockReopen() {
    String shop = "shop-states";
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    it.markMonthPresent(shop, e, YearMonth.parse(APRIL));
    String runId = it.payroll.create(OWNER, shop, new CreatePayrollRunRequest(APRIL)).getId();
    String lineId = lines(it, shop, runId).get(0).getId();

    BaseException draft = assertThrows(BaseException.class,
        () -> it.payments.pay(OWNER, shop, runId, payRequest(List.of(lineId), false)));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, draft.getErrorCode());

    it.payroll.finalise(OWNER, shop, runId);
    it.payments.pay(OWNER, shop, runId, payRequest(List.of(lineId), false));
    BaseException reopen = assertThrows(BaseException.class,
        () -> it.payroll.reopen(OWNER, shop, runId, "fix"));
    assertEquals(ErrorCode.HR_RUN_HAS_PAYMENTS, reopen.getErrorCode());

    it.payments.undo(OWNER, shop, runId, lineId, null);
    it.payroll.reopen(OWNER, shop, runId, "fix");
    // reopened: the run journal is reversed, the payment and its undo cancel out
    assertEquals(0, it.salaryPayable(shop).signum());
  }

  @Test
  void payslipsRegistersAndEmailsForAFinalisedRun() {
    String shop = "shop-docs";
    List<PayrollLine> lines = finalisedApril(it, shop, 2);
    String runId = lines.get(0).getRunId();
    it.template.updateFirst(Query.query(Criteria.where("_id").is(lines.get(0).getEmployeeId())),
        new Update().set("email", "staff1@example.com"), Employee.class);

    HrFile pdf = it.payslips.staffPayslip(OWNER, shop, runId, lines.get(0).getId());
    assertEquals(HrFile.PDF, pdf.contentType());
    assertTrue(new String(pdf.bytes(), 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));
    assertTrue(pdf.fileName().startsWith("payslip-EMP-00"));

    HrFile xlsx = it.reports.salaryRegister(OWNER, shop, runId, "xlsx");
    HrFile registerPdf = it.reports.salaryRegister(OWNER, shop, runId, "pdf");
    HrFile month = it.reports.monthExport(OWNER, shop, APRIL);
    HrFile balances = it.reports.leaveBalancesExport(OWNER, shop, "2025");
    assertTrue(xlsx.bytes().length > 0 && registerPdf.bytes().length > 0);
    assertTrue(month.bytes().length > 0 && balances.bytes().length > 0);
    assertEquals("salary-register-2025-04.pdf", registerPdf.fileName());

    EmailPayslipsRequest email = new EmailPayslipsRequest();
    email.setLineIds(List.of(lines.get(0).getId(), lines.get(1).getId()));
    EmailPayslipsResponse queued = it.payslips.email(OWNER, shop, runId, email);
    assertEquals(1, queued.getQueued());
    assertEquals(1, queued.getSkippedNoEmail().size());
    List<OutboundMessage> outbox = it.outbox.findAll();
    OutboundMessage msg = outbox.stream().filter(m -> m.getAttachmentRef() != null
        && shop.equals(m.getAttachmentRef().getShopId())).findFirst().orElseThrow();
    assertNull(msg.getAttachments(), "no PDF bytes stored with the message");
    assertEquals(lines.get(0).getId(), msg.getAttachmentRef().getLineId());
    assertTrue(msg.getSubject().contains("April 2025"));

    // the outbox's send-time render
    OutboundMessage.AttachmentRef ref = msg.getAttachmentRef();
    Attachment file = it.payslips.renderAttachment(
        new AttachmentRef(ref.getKind(), ref.getRunId(), ref.getLineId(), ref.getShopId()));
    assertNotNull(file);
    assertTrue(file.base64().length() > 100);

    // a draft run has no payslips
    Employee late = it.paidEmployee(shop, "EMP-009", "Late", "2025-01-01", "2025-01", "10000");
    it.markMonthPresent(shop, late, YearMonth.of(2025, 5));
    String may = it.payroll.create(OWNER, shop, new CreatePayrollRunRequest("2025-05")).getId();
    String mayLine = lines(it, shop, may).get(0).getId();
    BaseException notYet = assertThrows(BaseException.class,
        () -> it.payslips.staffPayslip(OWNER, shop, may, mayLine));
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, notYet.getErrorCode());
  }
}
