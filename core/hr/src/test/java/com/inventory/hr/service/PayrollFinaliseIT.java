package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Finalise / reopen against a real MongoDB and the real accounting module (design D8/D9,
 * Property 11): exactly one live PAYROLL_RUN journal through retry, a crash after the claim, a
 * race of two callers, reopen and re-finalise; the order rules; payments block reopen; unmarked
 * days block finalise. Runs only with {@code -Dstockkart.test.mongo.uri=…}; each test has its own
 * shop.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class PayrollFinaliseIT {

  private static final String OWNER = "owner-1";
  private static final YearMonth APRIL = YearMonth.of(2025, 4);
  private static final YearMonth MAY = YearMonth.of(2025, 5);
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("payroll");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  /** One employee paid ₹30,000 a month, every working day of the months marked Present. */
  private static Employee staffed(String shop, YearMonth... months) {
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    for (YearMonth m : months) {
      it.markMonthPresent(shop, e, m);
    }
    return e;
  }

  private static PayrollRunResponse draft(String shop, YearMonth month) {
    return it.payroll.create(OWNER, shop, new CreatePayrollRunRequest(month.toString()));
  }

  private static PayrollRun run(String shop, String runId) {
    return it.template.findById(runId, PayrollRun.class);
  }

  private static void assertOneLiveJournal(String shop, BigDecimal net) {
    List<JournalEntry> live = it.liveJournals(shop, JournalSource.PAYROLL_RUN);
    assertEquals(1, live.size(), "exactly one live payroll journal");
    JournalEntry entry = live.get(0);
    assertEquals(0, entry.getTotalDebit().compareTo(entry.getTotalCredit()));
    BigDecimal payable = entry.getLines().stream().filter(l -> "2400".equals(l.getAccountCode()))
        .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(0, net.compareTo(payable), "2400 carries the run's net pay");
  }

  @Test
  void aRetryReturnsTheFinalisedRunAndPostsNothingMore() {
    String shop = "shop-retry";
    staffed(shop, APRIL);
    PayrollRunResponse d = draft(shop, APRIL);
    assertEquals(PayrollRunStatus.DRAFT, d.getStatus());
    assertEquals(1, d.getLines().size());
    // calendar days: 26 working days + 4 Sundays paid = 30 of 30
    assertEquals(0, new BigDecimal("30000.00").compareTo(d.getLines().get(0).getNet()));

    PayrollRunResponse done = it.payroll.finalise(OWNER, shop, d.getId());
    PayrollRunResponse again = it.payroll.finalise(OWNER, shop, d.getId());

    assertEquals(PayrollRunStatus.FINALISED, done.getStatus());
    assertEquals(done.getJournalEntryId(), again.getJournalEntryId());
    assertTrue(done.getLines().get(0).isFrozen());
    assertOneLiveJournal(shop, new BigDecimal("30000"));
    JournalEntry entry = it.liveJournals(shop, JournalSource.PAYROLL_RUN).get(0);
    assertEquals(d.getId() + ":0", entry.getSourceId());
    assertEquals(APRIL.atEndOfMonth(), entry.getTxnDate());
    // the month is locked now
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> it.payroll.recalculate(OWNER, shop, d.getId())).getErrorCode());
  }

  @Test
  void aCrashAfterTheClaimAndPostIsResumedByTheNextCallWithoutASecondJournal() {
    String shop = "shop-crash";
    staffed(shop, APRIL);
    PayrollRunResponse d = draft(shop, APRIL);
    // a finalise that claimed, froze the lines, posted, then died before FINALISED
    Instant longAgo = Instant.now().minusSeconds(600);
    it.template.updateFirst(Query.query(Criteria.where("_id").is(d.getId())), new Update()
        .set("status", PayrollRunStatus.FINALISING).set("claimedAt", longAgo), PayrollRun.class);
    PayrollRun claimed = run(shop, d.getId());
    List<PayrollLine> lines = it.template.find(Query.query(Criteria.where("runId")
        .is(d.getId())), PayrollLine.class);
    assertNotNull(it.poster.postPayrollRun(shop, OWNER, claimed, lines));

    // a fresh claim is respected ...
    it.template.updateFirst(Query.query(Criteria.where("_id").is(d.getId())),
        new Update().set("claimedAt", Instant.now()), PayrollRun.class);
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> it.payroll.finalise(OWNER, shop, d.getId())).getErrorCode());
    // ... a stale one is taken over and finished
    it.template.updateFirst(Query.query(Criteria.where("_id").is(d.getId())),
        new Update().set("claimedAt", longAgo), PayrollRun.class);
    PayrollRunResponse done = it.payroll.finalise(OWNER, shop, d.getId());

    assertEquals(PayrollRunStatus.FINALISED, done.getStatus());
    assertOneLiveJournal(shop, new BigDecimal("30000"));
    assertEquals(1, done.getJournalHistory().size());
  }

  @Test
  void aShopsOwnAccountOnSalaryPayableStopsFinaliseAndLeavesTheDraft() {
    String shop = "shop-own-2400";
    // the shop made "2400 Rent deposit" before HR existed; the seeder then skips 2400
    com.inventory.accounting.domain.model.Account own =
        new com.inventory.accounting.domain.model.Account();
    own.setShopId(shop);
    own.setCode("2400");
    own.setName("Rent deposit");
    own.setType(com.inventory.accounting.domain.model.AccountType.LIABILITY);
    own.setNormalBalance(com.inventory.accounting.domain.model.NormalBalance.CREDIT);
    own.setSystem(false);
    own.setActive(true);
    it.template.insert(own);
    staffed(shop, APRIL);
    String runId = draft(shop, APRIL).getId();

    BaseException e = assertThrows(BaseException.class,
        () -> it.payroll.finalise(OWNER, shop, runId));

    assertEquals(ErrorCode.HR_ACCOUNT_CONFLICT, e.getErrorCode());
    assertTrue(e.getMessage().startsWith("Account 2400 in your chart of accounts"));
    assertEquals(PayrollRunStatus.DRAFT, run(shop, runId).getStatus());
    assertTrue(it.liveJournals(shop, JournalSource.PAYROLL_RUN).isEmpty());
  }

  @Test
  void twoCallersRacingFinaliseLeaveOneJournal() throws Exception {
    for (int round = 0; round < 5; round++) {
      String shop = "shop-race-" + round;
      staffed(shop, APRIL);
      String runId = draft(shop, APRIL).getId();
      ExecutorService pool = Executors.newFixedThreadPool(2);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Object>> results = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        results.add(pool.submit(() -> {
          go.await();
          try {
            return it.payroll.finalise(OWNER, shop, runId);
          } catch (BaseException e) {
            return e.getErrorCode();
          }
        }));
      }
      go.countDown();
      int finalised = 0;
      for (Future<Object> f : results) {
        Object r = f.get();
        if (r instanceof PayrollRunResponse resp) {
          assertEquals(PayrollRunStatus.FINALISED, resp.getStatus());
          finalised++;
        } else {
          assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, r);
        }
      }
      pool.shutdown();
      assertTrue(finalised >= 1);
      assertEquals(PayrollRunStatus.FINALISED, run(shop, runId).getStatus());
      assertOneLiveJournal(shop, new BigDecimal("30000"));
    }
  }

  @Test
  void reopenReversesAndAReFinalisePostsTheNewRevisionOnly() {
    String shop = "shop-reopen";
    staffed(shop, APRIL);
    String runId = draft(shop, APRIL).getId();
    PayrollRunResponse first = it.payroll.finalise(OWNER, shop, runId);

    PayrollRunResponse reopened = it.payroll.reopen(OWNER, shop, runId, "bonus missed");
    assertEquals(PayrollRunStatus.DRAFT, reopened.getStatus());
    assertEquals(1, reopened.getRevision());
    assertEquals(1, reopened.getReopenHistory().size());
    assertTrue(it.liveJournals(shop, JournalSource.PAYROLL_RUN).isEmpty(), "reversed");
    JournalEntry original = it.template.findById(first.getJournalEntryId(), JournalEntry.class);
    assertEquals(JournalStatus.REVERSED, original.getStatus());
    assertNotNull(reopened.getJournalHistory().get(0).getReversalEntryId());
    assertTrue(reopened.getLines().stream().noneMatch(l -> l.isFrozen()));

    // a bonus while it is a draft, then finalise again
    String lineId = reopened.getLines().get(0).getId();
    it.payroll.saveAdjustments(OWNER, shop, runId, lineId, new SavePayrollAdjustmentsRequest(
        List.of(new SavePayrollAdjustmentsRequest.Item("BONUS", "Festival", new BigDecimal(
            "500.50"))), null));
    PayrollRunResponse second = it.payroll.finalise(OWNER, shop, runId);

    assertEquals(PayrollRunStatus.FINALISED, second.getStatus());
    assertOneLiveJournal(shop, new BigDecimal("30501"));
    JournalEntry live = it.liveJournals(shop, JournalSource.PAYROLL_RUN).get(0);
    assertEquals(runId + ":1", live.getSourceId());
    // net 30,500.50 rounded up to 30,501: the 0.50 goes to Round-off Expense
    assertTrue(live.getLines().stream().anyMatch(l -> "5500".equals(l.getAccountCode())
        && new BigDecimal("0.50").compareTo(l.getDebit()) == 0));
    assertEquals(2, second.getJournalHistory().size());
    // reopening twice in a row is refused while it is a draft
    it.payroll.reopen(OWNER, shop, runId, "again");
    assertEquals(ErrorCode.HR_RUN_STATE_CONFLICT, assertThrows(BaseException.class,
        () -> it.payroll.reopen(OWNER, shop, runId, "again")).getErrorCode());
  }

  @Test
  void monthsFinaliseOldestFirstAndReopenNewestFirst() {
    String shop = "shop-order";
    staffed(shop, APRIL, MAY);
    String april = draft(shop, APRIL).getId();
    String may = draft(shop, MAY).getId();

    assertEquals(ErrorCode.HR_RUN_ORDER, assertThrows(BaseException.class,
        () -> it.payroll.finalise(OWNER, shop, may)).getErrorCode());
    assertEquals(PayrollRunStatus.DRAFT, run(shop, may).getStatus(), "no claim left behind");
    it.payroll.finalise(OWNER, shop, april);
    it.payroll.finalise(OWNER, shop, may);

    assertEquals(ErrorCode.HR_RUN_ORDER, assertThrows(BaseException.class,
        () -> it.payroll.reopen(OWNER, shop, april, "fix")).getErrorCode());
    assertEquals(PayrollRunStatus.FINALISED, run(shop, april).getStatus());
    it.payroll.reopen(OWNER, shop, may, "fix");
    it.payroll.reopen(OWNER, shop, april, "fix");
    assertTrue(it.liveJournals(shop, JournalSource.PAYROLL_RUN).isEmpty());
  }

  @Test
  void aPaidLineBlocksReopenAndKeepsTheRunFinalised() {
    String shop = "shop-paid";
    staffed(shop, APRIL);
    String runId = draft(shop, APRIL).getId();
    it.payroll.finalise(OWNER, shop, runId);
    it.template.updateMulti(Query.query(Criteria.where("runId").is(runId)),
        new Update().set("paymentStatus", SalaryPaymentStatus.PAID), PayrollLine.class);

    BaseException e = assertThrows(BaseException.class,
        () -> it.payroll.reopen(OWNER, shop, runId, "fix"));

    assertEquals(ErrorCode.HR_RUN_HAS_PAYMENTS, e.getErrorCode());
    PayrollRun after = run(shop, runId);
    assertEquals(PayrollRunStatus.FINALISED, after.getStatus());
    assertEquals(null, after.getClaimedAt());
    assertOneLiveJournal(shop, new BigDecimal("30000"));
  }

  @Test
  void unmarkedDaysBlockFinaliseAndAreListed() {
    String shop = "shop-unmarked";
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    it.markMonthPresent(shop, e, APRIL, "2025-04-09");
    String runId = draft(shop, APRIL).getId();

    assertEquals(ErrorCode.HR_UNMARKED_DAYS, assertThrows(BaseException.class,
        () -> it.payroll.finalise(OWNER, shop, runId)).getErrorCode());
    assertEquals(PayrollRunStatus.DRAFT, run(shop, runId).getStatus());
    assertEquals(List.of("2025-04-09"),
        it.payroll.unmarked(OWNER, shop, runId).getEmployees().get(0).getDates());
    assertTrue(it.liveJournals(shop, JournalSource.PAYROLL_RUN).isEmpty());
  }

  @Test
  void createReturnsTheExistingRunAndAnotherShopSeesNothing() {
    String shop = "shop-create";
    staffed(shop, APRIL);
    String first = draft(shop, APRIL).getId();
    assertEquals(first, draft(shop, APRIL).getId());
    assertTrue(it.payroll.list(OWNER, "shop-create-other").isEmpty());
    assertThrows(com.inventory.common.exception.ResourceNotFoundException.class,
        () -> it.payroll.get(OWNER, "shop-create-other", first));
  }
}
