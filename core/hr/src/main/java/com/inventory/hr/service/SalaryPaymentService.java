package com.inventory.hr.service;

import com.inventory.accounting.api.JournalEntryView;
import com.inventory.accounting.api.JournalSource;
import com.inventory.accounting.api.JournalStatus;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.rest.dto.request.RecordSalaryPaymentsRequest;
import com.inventory.hr.rest.dto.response.PayrollLineResponse;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse;
import com.inventory.hr.rest.dto.response.SalaryPaymentsResponse.LineStatus;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.SalaryPaymentValidator;
import com.inventory.metrics.MetricsWrapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Salary payments (R9, R11.3, decision 6): pay up to 50 lines of a FINALISED run as one batch
 * with one {@code SALARY_PAYMENT} journal ({@code sourceId = paymentBatchId}), and undo one paid
 * line with a compensating journal ({@code undo:<lineId>:<paymentSeq>}), never a reversal of the
 * whole batch.
 *
 * <p>No transactions (P8). Each line moves PENDING → PAYING → PAID and PAID → UNDOING → PENDING
 * through claims on the line ({@code paymentStatus}, {@code paymentBatchId}, {@code
 * paymentClaimedAt}); every step after a claim is idempotent because the journals are keyed by
 * their source. A claim older than {@link #STALE_CLAIM} belongs to a call that died: the next pay
 * or undo call on the run (or a reopen) takes it over and settles it by what the books say (see
 * {@link #finishStaleClaims}), so nothing is posted twice and nothing the user saw fail is
 * recorded later.
 *
 * <p>"Claim first, then check the other" against reopen: pay claims lines and then re-reads the
 * run (not FINALISED → claims released, 409); reopen claims the run and then refuses while any
 * line is PAID, PAYING or UNDOING. Whichever checks second sees the other and stops.
 */
@Slf4j
@Service
public class SalaryPaymentService {

  static final String ENTITY = "PAYROLL_LINE";
  /** A PAYING or UNDOING claim older than this belongs to a call that died. */
  static final Duration STALE_CLAIM = Duration.ofMinutes(2);

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private PayrollRunRepository runRepository;

  @Autowired private PayrollLineRepository lineRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private HrAccountingPoster poster;

  @Autowired private HrAuditService auditService;

  @Autowired private SalaryPaymentValidator validator;

  @Autowired private HrPayrollMapper mapper;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  // ---- pay -------------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs/{runId}/payments} (RECORD_PAYMENTS). Lines that are not PENDING are
   * reported and left alone, so a retry after a lost response pays nobody twice.
   */
  public SalaryPaymentsResponse pay(String userId, String shopId, String runId,
      RecordSalaryPaymentsRequest request) {
    guard.require(userId, shopId, HrPermission.RECORD_PAYMENTS);
    SalaryPaymentValidator.Checked input = validator.validate(request, HrDateUtils.today());
    return queryRunner.read("salary_pay", () -> {
      PayrollRun run = find(shopId, runId);
      requireFinalised(run);
      PayCycle.Range cycle = PayCycle.stored(run.getPeriod(), run.getFromDate(), run.getToDate());
      if (input.date().isBefore(cycle.from())) {
        // the first day of the salary month (PayCycle), as stored on the run
        throw new ValidationException("The payment date cannot be before " + cycle.fromText());
      }
      finishStaleClaims(userId, shopId, runId);

      List<String> ids = input.all() ? nextPendingIds(shopId, runId) : input.lineIds();
      SalaryPaymentsResponse response = new SalaryPaymentsResponse();
      if (ids.isEmpty()) {
        return response;
      }
      String batchId = new ObjectId().toHexString();
      Instant now = nowMillis();
      // one updateMany claims every requested line that is still unpaid; payment details go on
      // the line with the claim, so a call that dies after it can be finished by the next one
      Update claim = new Update().set("paymentStatus", SalaryPaymentStatus.PAYING)
          .inc("paymentSeq", 1).set("paymentBatchId", batchId).set("paymentClaimedAt", now)
          .set("paidAt", HrDateUtils.format(input.date())).set("paymentMethod", input.method())
          .set("paymentReference", input.reference()).set("paidByUserId", userId)
          .set("updatedAt", now);
      mongoTemplate.updateMulti(Query.query(Criteria.where("shopId").is(shopId).and("runId")
          .is(runId).and("_id").in(ids).and("paymentStatus")
          .in(SalaryPaymentStatus.PENDING, null)), claim, PayrollLine.class);

      // read every requested line once: ours are the ones carrying our batch id
      Query requested = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
          .and("_id").in(ids));
      requested.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      Map<String, PayrollLine> byId = new HashMap<>();
      for (PayrollLine l : mongoTemplate.find(requested, PayrollLine.class)) {
        byId.put(l.getId(), l);
      }
      List<PayrollLine> claimed = new ArrayList<>();
      for (String id : ids) {
        PayrollLine l = byId.get(id);
        if (l != null && batchId.equals(l.getPaymentBatchId())
            && l.getPaymentStatus() == SalaryPaymentStatus.PAYING) {
          claimed.add(l);
        }
      }
      if (claimed.isEmpty()) {
        response.setLines(outcomes(ids, byId, batchId));
        return response;
      }

      // claim first, then check the run: a reopen that claimed the run before our claim is
      // seen here; one that claims after it sees our PAYING lines and refuses
      PayrollRun again = find(shopId, runId);
      if (again.getStatus() != PayrollRunStatus.FINALISED) {
        releaseClaim(shopId, runId, batchId, now);
        metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, claimed.size(),
            "module", HrMetricsConstants.MODULE, "outcome", "run_not_finalised");
        throw stateConflict("This payroll run is being reopened. Try again in a minute");
      }

      JournalEntryView entry = postBatch(userId, shopId, runId, batchId, now, claimed, input);
      int paid = markPaid(shopId, runId, batchId, entry, now);
      if (paid < claimed.size()) {
        // our claim went stale while we posted and the next call took it over (see
        // finishStaleBatch): either it found our journal and marked the lines PAID itself, or it
        // found none yet and released them, and then our journal must not stay
        settleLostBatch(userId, shopId, runId, batchId, entry);
        response.setPaymentBatchId(batchId);
        response.setJournalEntryId(entry != null ? entry.id() : null);
        response.setPaid(claimed.size());
        response.setLines(outcomes(ids, byId, batchId));
        return response;
      }
      auditPaid(shopId, userId, claimed, batchId, entry, input.method(),
          HrDateUtils.format(input.date()));
      metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, claimed.size(),
          "module", HrMetricsConstants.MODULE, "outcome", "paid");
      log.info("[hr-payment] run {} of shop {}: batch {} paid {} line(s)", runId, shopId,
          batchId, paid);

      response.setPaymentBatchId(batchId);
      response.setJournalEntryId(entry != null ? entry.id() : null);
      response.setPaid(claimed.size());
      response.setLines(outcomes(ids, byId, batchId));
      return response;
    });
  }

  /**
   * One journal for the batch. A failed post may still have saved the entry (the posting engine
   * is several writes): only when none exists are the claims released; when we cannot tell, the
   * lines stay PAYING and the next call finishes them after {@link #STALE_CLAIM}.
   */
  private JournalEntryView postBatch(String userId, String shopId, String runId, String batchId,
      Instant claimedAt, List<PayrollLine> claimed, SalaryPaymentValidator.Checked input) {
    try {
      return poster.postPaymentBatch(shopId, userId, batchId, claimed, input.method(),
          input.date());
    } catch (RuntimeException e) {
      // a refused post (shop's own account on 2400) is counted by the poster, not as a failure
      if (!HrAccountingPoster.isAccountConflict(e)) {
        // exception class only: accounting messages can carry amounts
        log.error("[hr-payment] journal post failed for batch {} of shop {}: {}", batchId,
            shopId, e.getClass().getSimpleName());
        metrics.increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1,
            "module", HrMetricsConstants.MODULE, "source", JournalSource.SALARY_PAYMENT.name());
        metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, claimed.size(),
            "module", HrMetricsConstants.MODULE, "outcome", "post_failed");
      }
      Optional<JournalEntryView> saved;
      try {
        saved = poster.findPaymentJournal(shopId, batchId);
      } catch (RuntimeException lookupFailed) {
        throw e;
      }
      if (saved.isEmpty()) {
        releaseClaim(shopId, runId, batchId, claimedAt);
        throw e;
      }
      return saved.get();
    }
  }

  /**
   * After a lost claim: when the take-over marked the batch PAID it used our journal (fine);
   * when it released the lines, nothing is paid, so our journal is reversed and the caller is
   * told to try again.
   */
  private void settleLostBatch(String userId, String shopId, String runId, String batchId,
      JournalEntryView entry) {
    Query paidByUs = batchQuery(shopId, runId, batchId).addCriteria(
        Criteria.where("paymentStatus").is(SalaryPaymentStatus.PAID));
    if (mongoTemplate.exists(paidByUs, PayrollLine.class)) {
      return;
    }
    if (entry != null) {
      poster.reverseBySource(shopId, userId, JournalSource.SALARY_PAYMENT, batchId,
          "Salary payment interrupted, nothing paid");
    }
    metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, "outcome", "claim_lost");
    throw stateConflict("This payment took too long and was not recorded. Try again");
  }

  /** PAYING → PAID for the whole batch in one update; whoever finishes the batch writes it. */
  private int markPaid(String shopId, String runId, String batchId, JournalEntryView entry,
      Instant now) {
    Update paid = new Update().set("paymentStatus", SalaryPaymentStatus.PAID)
        .set("paymentJournalEntryId", entry != null ? entry.id() : null)
        .unset("paymentClaimedAt").set("updatedAt", now);
    return (int) mongoTemplate.updateMulti(batchQuery(shopId, runId, batchId).addCriteria(
        Criteria.where("paymentStatus").is(SalaryPaymentStatus.PAYING)),
        paid, PayrollLine.class).getModifiedCount();
  }

  /** Our claim only (a take-over moved {@code paymentClaimedAt}): back to PENDING. */
  private void releaseClaim(String shopId, String runId, String batchId, Instant claimedAt) {
    try {
      mongoTemplate.updateMulti(batchQuery(shopId, runId, batchId).addCriteria(
          Criteria.where("paymentStatus").is(SalaryPaymentStatus.PAYING)
              .and("paymentClaimedAt").is(claimedAt)), clearPayment(SalaryPaymentStatus.PENDING),
          PayrollLine.class);
    } catch (RuntimeException e) {
      // the stale-claim rule finishes the batch after STALE_CLAIM
      log.error("[hr-payment] could not release batch {} of shop {}: {}", batchId, shopId,
          e.getClass().getSimpleName());
    }
  }

  private void auditPaid(String shopId, String userId, List<PayrollLine> lines, String batchId,
      JournalEntryView entry, HrPaymentMethod method, String paidAt) {
    List<AuditEntry> entries = new ArrayList<>();
    for (PayrollLine l : lines) {
      // ids, method and date only: no amounts, reference or payment details
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("paymentStatus", SalaryPaymentStatus.PAID.name());
      after.put("paymentBatchId", batchId);
      after.put("method", method != null ? method.name() : null);
      after.put("paidAt", paidAt);
      after.put("journalEntryId", entry != null ? entry.id() : null);
      entries.add(new AuditEntry(shopId, userId, HrAuditAction.SALARY_PAID, ENTITY, l.getId(),
          l.getEmployeeId(), null, l.getPeriod(),
          Map.of("paymentStatus", SalaryPaymentStatus.PENDING.name()), after, null));
    }
    auditService.recordAll(entries);
  }

  /** Ids of the next unpaid lines (ordered by id, ≤ 50) for {@code all: true}. */
  private List<String> nextPendingIds(String shopId, String runId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
        .and("paymentStatus").in(SalaryPaymentStatus.PENDING, null))
        .with(Sort.by(Sort.Direction.ASC, "_id")).limit(SalaryPaymentValidator.MAX_LINES);
    query.fields().include("_id");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.find(query, PayrollLine.class).stream().map(PayrollLine::getId).toList();
  }

  private static List<SalaryPaymentsResponse.Line> outcomes(List<String> ids,
      Map<String, PayrollLine> byId, String batchId) {
    List<SalaryPaymentsResponse.Line> out = new ArrayList<>();
    for (String id : ids) {
      PayrollLine l = byId.get(id);
      if (l == null) {
        out.add(new SalaryPaymentsResponse.Line(id, LineStatus.NOT_FOUND,
            "This employee is not in this payroll run"));
      } else if (batchId.equals(l.getPaymentBatchId())) {
        out.add(new SalaryPaymentsResponse.Line(id, LineStatus.PAID, null));
      } else if (l.getPaymentStatus() == SalaryPaymentStatus.PAID) {
        out.add(new SalaryPaymentsResponse.Line(id, LineStatus.ALREADY_PAID,
            "Already marked as paid"));
      } else {
        out.add(new SalaryPaymentsResponse.Line(id, LineStatus.IN_PROGRESS,
            "A payment or undo for this employee is in progress. Try again in a minute"));
      }
    }
    return out;
  }

  // ---- undo ------------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs/{runId}/lines/{lineId}/payment/undo} (RECORD_PAYMENTS, R9.5): PAID →
   * UNDOING, a compensating journal for this line's net only, then PENDING with the payment
   * fields cleared.
   */
  public PayrollLineResponse undo(String userId, String shopId, String runId, String lineId,
      String reason) {
    guard.require(userId, shopId, HrPermission.RECORD_PAYMENTS);
    String why = validator.reason(reason);
    return queryRunner.read("salary_undo", () -> {
      finishStaleClaims(userId, shopId, runId);
      PayrollLine line = lineRepository.findByShopIdAndId(shopId, lineId)
          .filter(l -> runId.equals(l.getRunId()))
          .orElseThrow(() -> new ResourceNotFoundException("PayrollLine", "id", lineId));
      if (line.getPaymentStatus() != SalaryPaymentStatus.PAID) {
        throw stateConflict(line.getPaymentStatus() == SalaryPaymentStatus.PAYING
            || line.getPaymentStatus() == SalaryPaymentStatus.UNDOING
            ? "A payment or undo for this employee is in progress. Try again in a minute"
            : "This employee is not marked as paid");
      }
      int seq = seqOf(line);
      Instant now = nowMillis();
      PayrollLine claimed = mongoTemplate.findAndModify(Query.query(Criteria.where("shopId")
          .is(shopId).and("_id").is(lineId).and("paymentStatus").is(SalaryPaymentStatus.PAID)
          .and("paymentSeq").is(line.getPaymentSeq())),
          new Update().set("paymentStatus", SalaryPaymentStatus.UNDOING)
              .set("paymentClaimedAt", now).set("updatedAt", now),
          FindAndModifyOptions.options().returnNew(true), PayrollLine.class);
      if (claimed == null) {
        throw stateConflict("Someone else changed this payment. Reload and try again");
      }
      try {
        postUndo(userId, shopId, claimed, seq);
      } catch (RuntimeException e) {
        // nothing compensated: the line is still paid
        mongoTemplate.updateFirst(ownUndoClaim(shopId, lineId, seq, now),
            new Update().set("paymentStatus", SalaryPaymentStatus.PAID).unset("paymentClaimedAt"),
            PayrollLine.class);
        throw e;
      }
      PayrollLine pending = mongoTemplate.findAndModify(ownUndoClaim(shopId, lineId, seq, now),
          clearPayment(SalaryPaymentStatus.PENDING).set("updatedAt", now),
          FindAndModifyOptions.options().returnNew(true), PayrollLine.class);
      if (pending == null) {
        // our claim went stale and the next call took it over: it finished the undo with our
        // journal (line PENDING), or found none yet and put the line back to PAID, and then our
        // compensating journal must not stay
        PayrollLine current = lineRepository.findByShopIdAndId(shopId, lineId).orElse(claimed);
        if (current.getPaymentStatus() == SalaryPaymentStatus.PAID && seqOf(current) == seq) {
          poster.reverseBySource(shopId, userId, JournalSource.SALARY_PAYMENT,
              HrAccountingPoster.undoSourceId(lineId, seq), "Undo interrupted, line still paid");
          throw stateConflict("This undo took too long and was not recorded. Try again");
        }
        return mapper.toResponse(current);
      }
      auditUndone(shopId, userId, claimed, why);
      metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "outcome", "undone");
      return mapper.toResponse(pending);
    });
  }

  /**
   * The compensating entry of one undo. On failure: when the entry exists after all it is used,
   * else the error goes back to the caller (who returns the line to PAID).
   */
  private void postUndo(String userId, String shopId, PayrollLine line, int seq) {
    String sourceId = HrAccountingPoster.undoSourceId(line.getId(), seq);
    try {
      poster.postPaymentUndo(shopId, userId, line, seq, line.getPaymentMethod(),
          HrDateUtils.today());
    } catch (RuntimeException e) {
      if (HrAccountingPoster.isAccountConflict(e)) {
        // refused before posting (counted by the poster): nothing was saved
        throw e;
      }
      log.error("[hr-payment] undo journal failed for line {} of shop {}: {}", line.getId(),
          shopId, e.getClass().getSimpleName());
      metrics.increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "source", JournalSource.SALARY_PAYMENT.name());
      Optional<JournalEntryView> saved;
      try {
        saved = poster.findPaymentJournal(shopId, sourceId);
      } catch (RuntimeException lookupFailed) {
        throw e;
      }
      if (saved.isEmpty()) {
        throw e;
      }
    }
  }

  private void auditUndone(String shopId, String userId, PayrollLine line, String reason) {
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("paymentStatus", SalaryPaymentStatus.PAID.name());
    before.put("paymentBatchId", line.getPaymentBatchId());
    before.put("paymentSeq", seqOf(line));
    auditService.record(new AuditEntry(shopId, userId, HrAuditAction.SALARY_PAYMENT_UNDONE,
        ENTITY, line.getId(), line.getEmployeeId(), null, line.getPeriod(), before,
        Map.of("paymentStatus", SalaryPaymentStatus.PENDING.name()), reason));
  }

  private static Query ownUndoClaim(String shopId, String lineId, int seq, Instant claimedAt) {
    return Query.query(Criteria.where("shopId").is(shopId).and("_id").is(lineId)
        .and("paymentStatus").is(SalaryPaymentStatus.UNDOING).and("paymentSeq").is(seq)
        .and("paymentClaimedAt").is(claimedAt));
  }

  // ---- stale claims ----------------------------------------------------------------------------

  /**
   * Settles PAYING and UNDOING claims of the run older than {@link #STALE_CLAIM}: one read
   * (usually empty). Called first by every pay and undo call and by reopen, so a dead call never
   * blocks the run. Each claim is taken over first (its {@code paymentClaimedAt} moves to ours,
   * so the dead call's own release can no longer match), then settled by what the books say,
   * never by posting:
   *
   * <ul>
   *   <li>a batch whose journal exists is marked PAID (the call died after posting); a batch
   *       without one is released to PENDING (it died before, or was told it failed), so a
   *       payment the user saw fail is never recorded later behind their back;
   *   <li>an undo whose compensating journal exists is finished (PENDING); one without goes back
   *       to PAID.
   * </ul>
   *
   * A call that was only slow and finds its claim taken over checks the outcome and reverses its
   * own journal when the lines were released ({@link #settleLostBatch}, undo likewise). Failures
   * here are logged and skipped: the caller's own request goes on.
   */
  void finishStaleClaims(String userId, String shopId, String runId) {
    Instant now = nowMillis();
    Instant staleBefore = now.minus(STALE_CLAIM);
    Query stale = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
        .and("paymentStatus").in(SalaryPaymentStatus.PAYING, SalaryPaymentStatus.UNDOING)
        .and("paymentClaimedAt").not().gte(staleBefore));
    stale.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    List<PayrollLine> found = mongoTemplate.find(stale, PayrollLine.class);
    if (found.isEmpty()) {
      return;
    }
    Set<String> batches = new LinkedHashSet<>();
    for (PayrollLine l : found) {
      if (l.getPaymentStatus() == SalaryPaymentStatus.PAYING) {
        batches.add(l.getPaymentBatchId());
      } else {
        try {
          settleStaleUndo(userId, shopId, l, now, staleBefore);
        } catch (RuntimeException e) {
          log.error("[hr-payment] could not settle the stale undo of line {} of shop {}: {}",
              l.getId(), shopId, e.getClass().getSimpleName());
        }
      }
    }
    for (String batchId : batches) {
      try {
        settleStaleBatch(userId, shopId, runId, batchId, now, staleBefore);
      } catch (RuntimeException e) {
        log.error("[hr-payment] could not settle stale batch {} of shop {}: {}", batchId, shopId,
            e.getClass().getSimpleName());
      }
    }
  }

  private void settleStaleBatch(String userId, String shopId, String runId, String batchId,
      Instant now, Instant staleBefore) {
    if (batchId == null) {
      return;
    }
    mongoTemplate.updateMulti(batchQuery(shopId, runId, batchId).addCriteria(
        Criteria.where("paymentStatus").is(SalaryPaymentStatus.PAYING)
            .and("paymentClaimedAt").not().gte(staleBefore)),
        new Update().set("paymentClaimedAt", now), PayrollLine.class);
    Query ours = batchQuery(shopId, runId, batchId).addCriteria(
        Criteria.where("paymentStatus").is(SalaryPaymentStatus.PAYING)
            .and("paymentClaimedAt").is(now));
    List<PayrollLine> lines = mongoTemplate.find(ours, PayrollLine.class);
    if (lines.isEmpty()) {
      return; // someone else took it over first
    }
    Optional<JournalEntryView> posted = livePaymentJournal(shopId, batchId);
    if (posted.isEmpty()) {
      releaseClaim(shopId, runId, batchId, now);
      metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, lines.size(),
          "module", HrMetricsConstants.MODULE, "outcome", "stale_released");
      log.warn("[hr-payment] released stale batch {} of shop {} ({} line(s), nothing posted)",
          batchId, shopId, lines.size());
      return;
    }
    markPaid(shopId, runId, batchId, posted.get(), now);
    PayrollLine first = lines.get(0);
    // the payment as the dead call recorded it: its actor, method and date
    auditPaid(shopId, first.getPaidByUserId() != null ? first.getPaidByUserId() : userId, lines,
        batchId, posted.get(), first.getPaymentMethod(), first.getPaidAt());
    metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, lines.size(),
        "module", HrMetricsConstants.MODULE, "outcome", "stale_finished");
    log.warn("[hr-payment] finished stale batch {} of shop {} ({} line(s))", batchId, shopId,
        lines.size());
  }

  private void settleStaleUndo(String userId, String shopId, PayrollLine line, Instant now,
      Instant staleBefore) {
    int seq = seqOf(line);
    PayrollLine claimed = mongoTemplate.findAndModify(Query.query(Criteria.where("shopId")
        .is(shopId).and("_id").is(line.getId()).and("paymentStatus")
        .is(SalaryPaymentStatus.UNDOING).and("paymentSeq").is(line.getPaymentSeq())
        .and("paymentClaimedAt").not().gte(staleBefore)),
        new Update().set("paymentClaimedAt", now),
        FindAndModifyOptions.options().returnNew(true), PayrollLine.class);
    if (claimed == null) {
      return;
    }
    if (livePaymentJournal(shopId, HrAccountingPoster.undoSourceId(line.getId(), seq))
        .isEmpty()) {
      // the undo never posted: the line is still paid
      mongoTemplate.updateFirst(ownUndoClaim(shopId, line.getId(), seq, now),
          new Update().set("paymentStatus", SalaryPaymentStatus.PAID).unset("paymentClaimedAt"),
          PayrollLine.class);
      log.warn("[hr-payment] stale undo of line {} of shop {} rolled back (nothing posted)",
          line.getId(), shopId);
      return;
    }
    mongoTemplate.updateFirst(ownUndoClaim(shopId, line.getId(), seq, now),
        clearPayment(SalaryPaymentStatus.PENDING).set("updatedAt", now), PayrollLine.class);
    auditUndone(shopId, userId, claimed, "Finished after an interrupted undo");
    metrics.increment(HrMetricsConstants.SALARY_PAYMENTS_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, "outcome", "stale_finished");
    log.warn("[hr-payment] finished stale undo of line {} of shop {}", line.getId(), shopId);
  }

  /** The source's SALARY_PAYMENT entry unless it was reversed. */
  private Optional<JournalEntryView> livePaymentJournal(String shopId, String sourceId) {
    return poster.findPaymentJournal(shopId, sourceId)
        .filter(e -> e.status() != JournalStatus.REVERSED);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * The lines of one pay batch. {@code runId} comes first after the shop so the query rides the
   * (runId, employeeId) index and reads only this run's lines; a batch never spans runs.
   * {@code maxTimeMsec} bounds the finds; Spring does not pass it to updateMulti, which the
   * index bounds instead.
   */
  private static Query batchQuery(String shopId, String runId, String batchId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
        .and("paymentBatchId").is(batchId));
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return query;
  }

  /** Status plus every payment field cleared (paymentSeq is kept: it only ever grows). */
  private static Update clearPayment(SalaryPaymentStatus status) {
    return new Update().set("paymentStatus", status).unset("paymentBatchId").unset("paidAt")
        .unset("paymentMethod").unset("paymentReference").unset("paymentJournalEntryId")
        .unset("paidByUserId").unset("paymentClaimedAt");
  }

  private PayrollRun find(String shopId, String runId) {
    return runRepository.findByShopIdAndId(shopId, runId)
        .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", "id", runId));
  }

  private static void requireFinalised(PayrollRun run) {
    if (run.getStatus() != PayrollRunStatus.FINALISED) {
      throw stateConflict("Only a finalised payroll run can be paid. Finalise it first");
    }
  }

  private static int seqOf(PayrollLine line) {
    return line.getPaymentSeq() != null ? line.getPaymentSeq() : 0;
  }

  /** Mongo keeps milliseconds; claim times are compared for equality, so ours must match. */
  private static Instant nowMillis() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private static BaseException stateConflict(String message) {
    return new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT, message);
  }
}
