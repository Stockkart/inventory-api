package com.inventory.hr.service;

// Feature: hr-module, Property 6

import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Property 6 (R9.5, R11.8, decision 6): through any sequence of pay, retry, undo and calls that
 * die after claiming (before or after posting), every pay batch and every undo has exactly one
 * live SALARY_PAYMENT journal and Salary Payable (2400) nets to the net pay of the lines that
 * are not paid.
 *
 * <p>The payment paths are Mongo writes (claims, {@code updateMany}, the accounting module), so
 * this property runs against the throwaway MongoDB of the {@code *IT} tests: without {@code
 * -Dstockkart.test.mongo.uri} every try passes without doing anything (jqwik has no
 * system-property condition; the ITs use JUnit's {@code @EnabledIfSystemProperty}).
 */
class SalaryPaymentProperties {

  private static final String URI = "stockkart.test.mongo.uri";
  private static final String OWNER = "owner-1";
  private static final int STAFF = 3;
  private static final AtomicInteger SHOPS = new AtomicInteger();
  private static HrMongoItSupport it;

  enum Kind { PAY, PAY_ALL, UNDO, CRASH_PAY, CRASH_PAY_POSTED, CRASH_UNDO_POSTED }

  /** {@code mask} picks lines (bit i = line i); {@code index} picks one line. */
  record Action(Kind kind, int mask, int index) {}

  @BeforeContainer
  static void setUp() {
    if (System.getProperty(URI) != null) {
      it = new HrMongoItSupport("payment_props");
    }
  }

  @AfterContainer
  static void tearDown() {
    if (it != null) {
      it.close();
    }
  }

  @Property(tries = 15)
  void everyBatchAndUndoHasOneLiveJournalAndPayableNetsToTheUnpaidLines(
      @ForAll("actions") List<Action> actions) {
    if (it == null) {
      return;
    }
    String shop = "prop-shop-" + SHOPS.incrementAndGet();
    List<PayrollLine> lines = SalaryPaymentIT.finalisedApril(it, shop, STAFF);
    String runId = lines.get(0).getRunId();
    List<String> ids = lines.stream().map(PayrollLine::getId).toList();
    boolean crashed = false;
    for (Action a : actions) {
      try {
        switch (a.kind()) {
          case PAY -> it.payments.pay(OWNER, shop, runId,
              SalaryPaymentIT.payRequest(pick(ids, a.mask()), false));
          case PAY_ALL -> it.payments.pay(OWNER, shop, runId,
              SalaryPaymentIT.payRequest(List.of(), true));
          case UNDO -> it.payments.undo(OWNER, shop, runId, ids.get(a.index()), null);
          case CRASH_PAY -> crashedPay(shop, pick(ids, a.mask()), false);
          case CRASH_PAY_POSTED -> crashedPay(shop, pick(ids, a.mask()), true);
          case CRASH_UNDO_POSTED -> crashedUndo(shop, ids.get(a.index()));
        }
      } catch (BaseException expected) {
        // e.g. undo of a line that is not paid: refused, nothing written
      }
      crashed |= a.kind().name().startsWith("CRASH");
      if (!crashed) {
        SalaryPaymentIT.assertBooksMatch(it, shop, runId);
      }
    }
    // the next real call finishes every dead claim; then the books must match
    it.payments.pay(OWNER, shop, runId, SalaryPaymentIT.payRequest(List.of(), true));
    SalaryPaymentIT.assertBooksMatch(it, shop, runId);
  }

  /** A pay call that claimed the PENDING lines among {@code ids} and died (optionally posted). */
  private static void crashedPay(String shop, List<String> ids, boolean posted) {
    String batch = new ObjectId().toHexString();
    it.template.updateMulti(Query.query(Criteria.where("shopId").is(shop).and("_id").in(ids)
        .and("paymentStatus").is(SalaryPaymentStatus.PENDING)),
        new Update().set("paymentStatus", SalaryPaymentStatus.PAYING).inc("paymentSeq", 1)
            .set("paymentBatchId", batch).set("paymentClaimedAt", stale())
            .set("paidAt", "2025-05-01").set("paymentMethod", HrPaymentMethod.CASH)
            .set("paidByUserId", OWNER), PayrollLine.class);
    List<PayrollLine> claimed = it.template.find(Query.query(Criteria.where("shopId").is(shop)
        .and("paymentBatchId").is(batch)), PayrollLine.class);
    if (posted && !claimed.isEmpty()) {
      it.poster.postPaymentBatch(shop, OWNER, batch, claimed, HrPaymentMethod.CASH,
          LocalDate.of(2025, 5, 1));
    }
  }

  /** An undo that claimed a PAID line, posted its entry and died. */
  private static void crashedUndo(String shop, String id) {
    PayrollLine line = it.template.findAndModify(Query.query(Criteria.where("shopId").is(shop)
        .and("_id").is(id).and("paymentStatus").is(SalaryPaymentStatus.PAID)),
        new Update().set("paymentStatus", SalaryPaymentStatus.UNDOING)
            .set("paymentClaimedAt", stale()),
        org.springframework.data.mongodb.core.FindAndModifyOptions.options().returnNew(true),
        PayrollLine.class);
    if (line != null) {
      it.poster.postPaymentUndo(shop, OWNER, line, line.getPaymentSeq(), line.getPaymentMethod(),
          LocalDate.of(2025, 5, 3));
    }
  }

  private static Instant stale() {
    return Instant.now().minusSeconds(SalaryPaymentService.STALE_CLAIM.toSeconds() + 60);
  }

  private static List<String> pick(List<String> ids, int mask) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < ids.size(); i++) {
      if ((mask & (1 << i)) != 0) {
        out.add(ids.get(i));
      }
    }
    return out.isEmpty() ? List.of(ids.get(0)) : out;
  }

  @Provide
  Arbitrary<List<Action>> actions() {
    Arbitrary<Action> action = Combinators.combine(Arbitraries.of(Kind.class),
        Arbitraries.integers().between(1, (1 << STAFF) - 1),
        Arbitraries.integers().between(0, STAFF - 1)).as(Action::new);
    return action.list().ofMinSize(1).ofMaxSize(8);
  }
}
