package com.inventory.hr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.JournalStatus;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.response.AdvanceResponse;
import com.inventory.hr.rest.dto.response.AdvanceStatementResponse;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Advances against a real MongoDB and the real accounting module (R7, R11.2, Property 10):
 * outstanding from finalised recoveries, the STAFF_ADVANCE journal, cancel with reversal, the
 * idempotent re-post of a missing journal, the statement and shop isolation. Runs only with
 * {@code -Dstockkart.test.mongo.uri=…}.
 */
@EnabledIfSystemProperty(named = "stockkart.test.mongo.uri", matches = ".+")
class AdvanceServiceIT {

  private static final String OWNER = "owner-1";
  private static HrMongoItSupport it;

  @BeforeAll
  static void setUp() {
    it = new HrMongoItSupport("advance");
  }

  @AfterAll
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  private static AdvanceResponse record(String shop, Employee e, String date, String amount,
      String monthly, String paidBy) {
    return it.advances.record(OWNER, shop, new RecordAdvanceRequest(e.getId(), date,
        new BigDecimal(amount), new BigDecimal(monthly), paidBy, null));
  }

  private static BigDecimal line(JournalEntry entry, String code, boolean debit) {
    return entry.getLines().stream().filter(l -> code.equals(l.getAccountCode()))
        .map(debit ? JournalLine::getDebit : JournalLine::getCredit)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  @Test
  void payrollRecoversFifoAndOutstandingFollowsFinalisedRunsOnly() {
    String shop = "shop-adv-recover";
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    it.markMonthPresent(shop, e, YearMonth.of(2025, 4));
    AdvanceResponse older = record(shop, e, "2025-03-10", "3000", "1000", "UPI");
    AdvanceResponse newer = record(shop, e, "2025-03-20", "500", "500", "CASH");
    assertTrue(older.isJournalPosted());

    JournalEntry posted = it.accounting.findBySource(shop, JournalSource.STAFF_ADVANCE,
        older.getId()).orElseThrow();
    assertEquals(0, new BigDecimal("3000").compareTo(line(posted, "1250", true)));
    assertEquals(0, new BigDecimal("3000").compareTo(line(posted, "1110", false)), "UPI → Bank");
    assertTrue(posted.getLines().stream().allMatch(l -> "EMP-001".equals(l.getMemo())));

    PayrollRunResponse draft = it.payroll.create(OWNER, shop,
        new CreatePayrollRunRequest("2025-04"));
    // FIFO: 1,000 from the older advance, then 500 from the newer one
    assertEquals(2, draft.getLines().get(0).getAdvanceRecoveries().size());
    assertEquals(0, new BigDecimal("28500.00").compareTo(draft.getLines().get(0).getNet()));
    // a draft recovers nothing yet
    assertEquals(0, new BigDecimal("3000").compareTo(outstanding(shop, e, older.getId())));

    it.payroll.finalise(OWNER, shop, draft.getId());
    assertEquals(0, new BigDecimal("2000").compareTo(outstanding(shop, e, older.getId())));
    assertEquals(0, BigDecimal.ZERO.compareTo(outstanding(shop, e, newer.getId())));
    JournalEntry run = it.liveJournals(shop, JournalSource.PAYROLL_RUN).get(0);
    assertEquals(0, new BigDecimal("1500").compareTo(line(run, "1250", false)));
    assertEquals(0, new BigDecimal("30000").compareTo(line(run, "5700", true)));

    AdvanceStatementResponse statement = it.advances.statement(OWNER, shop, e.getId());
    assertEquals(4, statement.getRows().size());
    assertEquals(0, new BigDecimal("2000").compareTo(statement.getOutstanding()));
    assertEquals(AdvanceStatementResponse.Kind.RECOVERED,
        statement.getRows().get(3).getKind());
    assertEquals("2025-04-30", statement.getRows().get(3).getDate());

    BaseException blocked = assertThrows(BaseException.class,
        () -> it.advances.cancel(OWNER, shop, older.getId(), "given by mistake"));
    assertEquals(ErrorCode.HR_ADVANCE_HAS_RECOVERIES, blocked.getErrorCode());
    assertEquals(AdvanceStatus.ACTIVE,
        it.template.findById(older.getId(), Advance.class).getStatus(), "claim given back");
  }

  @Test
  void cancelReversesTheJournalAndIsSafeToRepeat() {
    String shop = "shop-adv-cancel";
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    AdvanceResponse a = record(shop, e, "2025-03-10", "500", "500", "CASH");

    AdvanceResponse cancelled = it.advances.cancel(OWNER, shop, a.getId(), "returned in cash");
    AdvanceResponse again = it.advances.cancel(OWNER, shop, a.getId(), "returned in cash");

    assertEquals(AdvanceStatus.CANCELLED, cancelled.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(again.getOutstanding()));
    JournalEntry original = it.accounting.findBySource(shop, JournalSource.STAFF_ADVANCE,
        a.getId()).orElseThrow();
    assertEquals(JournalStatus.REVERSED, original.getStatus());
    assertTrue(it.liveJournals(shop, JournalSource.STAFF_ADVANCE).isEmpty());
    assertEquals(1, it.liveJournals(shop, JournalSource.REVERSAL).size());
    assertTrue(it.template.findById(a.getId(), Advance.class).getJournalReversed());
  }

  @Test
  void aMissingJournalIsPostedOnceByTheNextList() {
    String shop = "shop-adv-repost";
    Employee e = it.paidEmployee(shop, "EMP-001", "Asha", "2025-01-01", "2025-01", "30000");
    // an advance whose post failed after the insert
    Advance pending = new Advance();
    pending.setShopId(shop);
    pending.setEmployeeId(e.getId());
    pending.setDate("2025-03-05");
    pending.setAmount(new BigDecimal("750.50"));
    pending.setMonthlyDeduction(new BigDecimal("250"));
    pending.setPaidBy(HrPaymentMethod.BANK);
    pending.setStatus(AdvanceStatus.ACTIVE);
    pending.setCreatedAt(Instant.now());
    String id = it.template.insert(pending).getId();
    assertNull(it.template.findById(id, Advance.class).getJournalEntryId());

    List<AdvanceResponse> first = it.advances.list(OWNER, shop, e.getId());
    List<AdvanceResponse> second = it.advances.list(OWNER, shop, null);

    assertTrue(first.get(0).isJournalPosted());
    assertTrue(second.get(0).isJournalPosted());
    assertNotNull(it.template.findById(id, Advance.class).getJournalEntryId());
    assertEquals(1, it.liveJournals(shop, JournalSource.STAFF_ADVANCE).size());
    assertEquals("EMP-001", first.get(0).getEmployeeCode());
    assertEquals(0, new BigDecimal("750.50").compareTo(first.get(0).getOutstanding()));
  }

  @Test
  void anotherShopsAdvancesAreNeverListedOrCancelled() {
    Employee b = it.paidEmployee("shop-adv-b", "EMP-001", "Bala", "2025-01-01", "2025-01",
        "20000");
    AdvanceResponse inB = record("shop-adv-b", b, "2025-03-10", "100", "100", "CASH");

    assertTrue(it.advances.list(OWNER, "shop-adv-a", null).isEmpty());
    assertThrows(ResourceNotFoundException.class,
        () -> it.advances.cancel(OWNER, "shop-adv-a", inB.getId(), "x"));
    assertThrows(ResourceNotFoundException.class,
        () -> it.advances.statement(OWNER, "shop-adv-a", b.getId()));
    assertThrows(ResourceNotFoundException.class, () -> it.advances.record(OWNER, "shop-adv-a",
        new RecordAdvanceRequest(b.getId(), "2025-03-10", BigDecimal.TEN, BigDecimal.ONE,
            "CASH", null)));
    assertFalse(it.advances.list(OWNER, "shop-adv-b", null).isEmpty());
  }

  private static BigDecimal outstanding(String shop, Employee e, String advanceId) {
    return it.advances.list(OWNER, shop, e.getId()).stream()
        .filter(a -> a.getId().equals(advanceId)).findFirst().orElseThrow().getOutstanding();
  }
}
