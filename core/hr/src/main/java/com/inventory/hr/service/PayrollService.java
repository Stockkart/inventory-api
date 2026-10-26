package com.inventory.hr.service;

import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.JournalSource;
import com.inventory.accounting.domain.model.SystemAccountCode;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.common.exception.ValidationException;
import com.inventory.hr.domain.model.AttendanceDay;
import com.inventory.hr.domain.model.LeaveRequest;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.LeaveRequestStatus;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.model.enums.SalaryPaymentStatus;
import com.inventory.hr.domain.repository.PayrollLineRepository;
import com.inventory.hr.domain.repository.PayrollRunRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.rest.dto.request.CreatePayrollRunRequest;
import com.inventory.hr.rest.dto.request.SavePayrollAdjustmentsRequest;
import com.inventory.hr.rest.dto.response.PayrollLineResponse;
import com.inventory.hr.rest.dto.response.PayrollRunResponse;
import com.inventory.hr.rest.dto.response.UnmarkedDaysResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.service.payroll.PayrollCalculator;
import com.inventory.hr.service.payroll.PayrollInput;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.HrMoneyUtils;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.PayrollAdjustmentValidator;
import com.inventory.metrics.MetricsWrapper;
import com.mongodb.bulk.BulkWriteError;
import com.mongodb.bulk.BulkWriteUpsert;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.BulkOperationException;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Payroll runs (R8, R11.1, design D8/D9): drafts, recalculation, review adjustments, the unmarked
 * list, and the finalise / reopen state machine.
 *
 * <p>No transactions (P8). Exactly-once posting comes from two things: a claim on the run
 * ({@code findAndModify} DRAFT → FINALISING, FINALISED → REOPENING; a claim older than {@link
 * #STALE_CLAIM} may be taken over, so a crashed call is resumed by the next one), and the journal
 * being keyed {@code runId:revision}, so a repeated post returns the entry already posted. Every
 * step after a claim is idempotent.
 *
 * <p>"Claim first, then check the other": finalise claims the run and then re-reads the other
 * runs (order rule) and later the attendance and leave written after the claim; reopen claims and
 * then reads the run's lines for payments; salary payment (task 9) claims lines and then reads
 * the run. Whichever of two racing calls checks second sees the other's claim and stops.
 *
 * <p>Lines are frozen by finalise ({@code frozenAt} = the claim time). Draft writes
 * (recalculate, adjustments) only touch lines whose {@code frozenAt} is null and whose {@code
 * updatedAt} is still the one they read; a write that loses either race fails on the unique
 * {@code (runId, employeeId)} index and is reported as a concurrent change.
 */
@Slf4j
@Service
public class PayrollService {

  static final String ENTITY = "PAYROLL_RUN";
  /** A FINALISING or REOPENING claim older than this belongs to a call that died. */
  static final Duration STALE_CLAIM = Duration.ofMinutes(2);
  static final int DUPLICATE_KEY = 11000;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private PayrollRunRepository runRepository;

  @Autowired private PayrollLineRepository lineRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private PayrollInputReader inputReader;

  @Autowired private HrAccountingPoster poster;

  @Autowired private HrAuditService auditService;

  @Autowired private PayrollAdjustmentValidator validator;

  @Autowired private HrPayrollMapper mapper;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  @Autowired private SalaryPaymentService salaryPaymentService;

  // ---- reads -----------------------------------------------------------------------------------

  /** {@code GET /payroll-runs}, newest month first, without lines (RUN_PAYROLL or RECORD_PAYMENTS). */
  public List<PayrollRunResponse> list(String userId, String shopId) {
    guard.requireOneOf(userId, shopId, HrPermission.RUN_PAYROLL, HrPermission.RECORD_PAYMENTS);
    return queryRunner.read("payroll_list", () -> {
      Query query = Query.query(Criteria.where("shopId").is(shopId))
          .with(Sort.by(Sort.Direction.DESC, "period"));
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      return mongoTemplate.find(query, PayrollRun.class).stream().map(mapper::toResponse)
          .toList();
    });
  }

  /** {@code GET /payroll-runs/{runId}} with its lines (RUN_PAYROLL or RECORD_PAYMENTS). */
  public PayrollRunResponse get(String userId, String shopId, String runId) {
    guard.requireOneOf(userId, shopId, HrPermission.RUN_PAYROLL, HrPermission.RECORD_PAYMENTS);
    return queryRunner.read("payroll_get", () -> withLines(shopId, find(shopId, runId)));
  }

  /**
   * {@code GET /payroll-runs/{runId}/unmarked} (R8.12): who still has Unmarked days in the month
   * and which. Days after today are left out: they cannot be marked yet (finalise of a month that
   * is not over is refused separately). Attendance reads only (5 queries).
   */
  public UnmarkedDaysResponse unmarked(String userId, String shopId, String runId) {
    guard.requireOneOf(userId, shopId, HrPermission.RUN_PAYROLL, HrPermission.RECORD_PAYMENTS);
    return queryRunner.read("payroll_unmarked", () -> {
      PayrollRun run = find(shopId, runId);
      PayrollInputReader.Snapshot snapshot = inputReader.read(shopId,
          YearMonth.parse(run.getPeriod()), null, List.of(), null, false);
      UnmarkedDaysResponse response = new UnmarkedDaysResponse();
      response.setPeriod(run.getPeriod());
      String today = HrDateUtils.format(HrDateUtils.today());
      for (PayrollInput.EmployeeInput in : snapshot.input().employees()) {
        // yyyy-MM-dd strings compare in date order
        List<String> dates = PayrollCalculator.calculate(snapshot.input(), in).unmarkedDates()
            .stream().filter(d -> d.compareTo(today) <= 0).toList();
        if (!dates.isEmpty()) {
          response.getEmployees().add(new UnmarkedDaysResponse.Row(in.employee().getId(),
              in.employee().getCode(), in.employee().getName(), new ArrayList<>(dates)));
        }
      }
      return response;
    });
  }

  // ---- draft -----------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs {period}} (R8.1, R8.2): the month's draft, calculated, or the run
   * that already exists for the month as it stands (unique {@code (shopId, period)}).
   */
  public PayrollRunResponse create(String userId, String shopId, CreatePayrollRunRequest request) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    YearMonth month = validator.requirePeriod(request != null ? request.getPeriod() : null,
        YearMonth.now(HrDateUtils.ZONE));
    String period = HrDateUtils.format(month.atDay(1)).substring(0, 7);
    return queryRunner.read("payroll_create", () -> {
      PayrollRun existing = runRepository.findByShopIdAndPeriod(shopId, period).orElse(null);
      if (existing != null) {
        return withLines(shopId, existing);
      }
      PayrollRun run = new PayrollRun();
      run.setShopId(shopId);
      run.setPeriod(period);
      run.setStatus(PayrollRunStatus.DRAFT);
      run.setRevision(0);
      run.setCreatedAt(Instant.now());
      run.setCreatedByUserId(userId);
      try {
        run = runRepository.insert(run);
      } catch (DuplicateKeyException e) {
        // two creates raced: the other one's run is the month's run
        return withLines(shopId, runRepository.findByShopIdAndPeriod(shopId, period)
            .orElseThrow(() -> e));
      }
      PayrollRun created = run;
      List<PayrollLine> lines = recalculateDraft(shopId, created, "create");
      return response(created, lines);
    });
  }

  /** {@code POST /payroll-runs/{runId}/recalculate} (R8.11); DRAFT only. */
  public PayrollRunResponse recalculate(String userId, String shopId, String runId) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    return queryRunner.read("payroll_recalculate", () -> {
      PayrollRun run = find(shopId, runId);
      requireDraft(run);
      List<PayrollLine> lines = recalculateDraft(shopId, run, "recalculate");
      return response(run, lines);
    });
  }

  /**
   * {@code PUT /payroll-runs/{runId}/lines/{lineId}/adjustments} (R7.4, R8.9): replaces the line's
   * manual items and advance override, recalculates that one line and the run totals. Refused
   * when net pay would go below zero.
   */
  public PayrollLineResponse saveAdjustments(String userId, String shopId, String runId,
      String lineId, SavePayrollAdjustmentsRequest request) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    List<PayrollLine.ManualItem> items = validator.validate(request);
    return queryRunner.read("payroll_adjust", () -> {
      PayrollRun run = find(shopId, runId);
      requireDraft(run);
      PayrollLine line = lineRepository.findByShopIdAndId(shopId, lineId)
          .filter(l -> runId.equals(l.getRunId()))
          .orElseThrow(() -> new ResourceNotFoundException("PayrollLine", "id", lineId));
      if (line.getFrozenAt() != null) {
        throw stateConflict("This payroll run is being finalised. Try again in a minute");
      }
      long started = System.nanoTime();
      PayrollInputReader.Snapshot snapshot = inputReader.read(shopId,
          YearMonth.parse(run.getPeriod()), runId, finalisedIds(runs(shopId)),
          Set.of(line.getEmployeeId()), true);
      PayrollInput.EmployeeInput in = snapshot.input().employees().stream().findFirst()
          .orElseThrow(() -> new ValidationException(
              "This employee was not employed in the month. Recalculate the payroll"));
      PayrollInput.EmployeeInput adjusted = new PayrollInput.EmployeeInput(in.employee(),
          in.marks(), in.leave(), items, request.getAdvanceOverride(), in.advances());
      PayrollLine computed = PayrollCalculator.calculate(snapshot.input(), adjusted).line();
      validator.requireNonNegativeNet(computed);
      recordCalc("adjust", started);

      Instant now = nowMillis();
      // only the version we read, and only while not frozen by a finalise
      Query match = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(lineId)
          .and("frozenAt").is(null).and("updatedAt").is(line.getUpdatedAt()));
      if (mongoTemplate.updateFirst(match, lineUpdate(computed, now), PayrollLine.class)
          .getModifiedCount() != 1) {
        throw concurrentUpdate();
      }
      updateDraftTotals(shopId, run, totalsOf(lineRepository.findByShopIdAndRunId(shopId, runId)));
      computed.setId(lineId);
      computed.setRunId(runId);
      computed.setShopId(shopId);
      computed.setUpdatedAt(now);
      computed.setPaymentStatus(line.getPaymentStatus());
      return mapper.toResponse(computed);
    });
  }

  /** Calculates every line of a DRAFT run and writes them with the run totals. */
  private List<PayrollLine> recalculateDraft(String shopId, PayrollRun run, String operation) {
    long started = System.nanoTime();
    Computed computed = compute(shopId, run, finalisedIds(runs(shopId)));
    recordCalc(operation, started);
    Instant now = nowMillis();
    BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
        PayrollLine.class);
    Set<String> keep = new HashSet<>();
    for (PayrollLine line : computed.lines()) {
      keep.add(line.getEmployeeId());
      PayrollLine old = computed.existing().get(line.getEmployeeId());
      Criteria match = Criteria.where("shopId").is(shopId).and("runId").is(run.getId())
          .and("employeeId").is(line.getEmployeeId()).and("frozenAt").is(null);
      if (old != null) {
        // optimistic: the line as we read it; a newer adjustment makes this upsert miss and
        // fail on the unique (runId, employeeId) index instead of overwriting it
        match = match.and("updatedAt").is(old.getUpdatedAt());
      }
      bulk.upsert(Query.query(match), lineUpdate(line, now));
    }
    for (PayrollLine old : computed.existing().values()) {
      if (!keep.contains(old.getEmployeeId())) {
        // no longer employed in the month (e.g. join date moved): a draft line goes
        bulk.remove(Query.query(Criteria.where("shopId").is(shopId).and("_id").is(old.getId())
            .and("frozenAt").is(null)));
      }
    }
    if (!computed.lines().isEmpty() || !computed.existing().isEmpty()) {
      try {
        // ids of inserted lines come back with the write (upserts are the first operations, one
        // per computed line, so the index is the line's), so the response needs no re-read
        com.mongodb.bulk.BulkWriteResult written = bulk.execute();
        List<BulkWriteUpsert> upserts = written != null ? written.getUpserts() : List.of();
        for (BulkWriteUpsert upsert : upserts) {
          if (upsert.getIndex() < computed.lines().size() && upsert.getId().isObjectId()) {
            computed.lines().get(upsert.getIndex())
                .setId(upsert.getId().asObjectId().getValue().toHexString());
          }
        }
      } catch (BulkOperationException e) {
        if (allDuplicates(e)) {
          throw concurrentUpdate();
        }
        throw e;
      }
    }
    PayrollRun.Totals totals = totalsOf(computed.lines());
    updateDraftTotals(shopId, run, totals);
    run.setTotals(totals);
    run.setCalculatedAt(now);
    // the lines as just written: computed fields, our updatedAt, and the payment state they kept
    // (a draft line is never paid)
    for (PayrollLine line : computed.lines()) {
      PayrollLine old = computed.existing().get(line.getEmployeeId());
      line.setUpdatedAt(now);
      line.setPaymentStatus(old != null && old.getPaymentStatus() != null
          ? old.getPaymentStatus() : SalaryPaymentStatus.PENDING);
      line.setPaymentSeq(old != null && old.getPaymentSeq() != null ? old.getPaymentSeq() : 0);
    }
    return computed.lines();
  }

  // ---- finalise --------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs/{runId}/finalise} (R8.12–R8.14, R11.1): order check, claim
   * (DRAFT or a stale FINALISING → FINALISING), re-check the order, recompute and freeze the
   * lines, re-check attendance and leave written after the claim (recompute once more if any),
   * post the journal, then FINALISED. Calling it again on a FINALISED run returns the run.
   */
  public PayrollRunResponse finalise(String userId, String shopId, String runId) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    return queryRunner.read("payroll_finalise", () -> {
      PayrollRun run = find(shopId, runId);
      if (run.getStatus() == PayrollRunStatus.FINALISED) {
        return withLines(shopId, run);
      }
      Instant now = nowMillis();
      if (run.getStatus() == PayrollRunStatus.REOPENING
          || (run.getStatus() == PayrollRunStatus.FINALISING && !isStale(run, now))) {
        throw stateConflict("This payroll run is being finalised or reopened."
            + " Try again in a minute");
      }
      assertMonthOver(run);
      // cheap early answer; repeated after the claim, which is the check that counts
      assertFinaliseOrder(run, runs(shopId));

      PayrollRun claimed = claim(shopId, runId, now, PayrollRunStatus.DRAFT,
          PayrollRunStatus.FINALISING);
      if (claimed == null) {
        throw stateConflict("Someone else is finalising this payroll run");
      }
      Computed computed;
      try {
        // claim first, then check: an earlier month reopened, or a later one finalised, after
        // our first look is seen here (that call checks after its own claim and sees ours)
        List<PayrollRun> runs = runs(shopId);
        assertFinaliseOrder(claimed, runs);
        List<String> finalised = finalisedIds(runs);
        long started = System.nanoTime();
        computed = compute(shopId, claimed, finalised);
        recordCalc("finalise", started);
        assertFinalisable(computed);
        assertStillClaimed(shopId, runId, now);
        writeFrozenLines(shopId, claimed, computed, now);
        // a mark or leave decision that passed its lock check just before our claim lands with a
        // markedAt / decidedAt after it; recompute once so the frozen lines include it
        if (changedSince(shopId, YearMonth.parse(claimed.getPeriod()), now)) {
          started = System.nanoTime();
          computed = compute(shopId, claimed, finalised);
          recordCalc("finalise", started);
          assertFinalisable(computed);
          assertStillClaimed(shopId, runId, now);
          writeFrozenLines(shopId, claimed, computed, now);
        }
        assertStillClaimed(shopId, runId, now);
      } catch (RuntimeException e) {
        releaseFinaliseClaim(shopId, runId, now);
        throw e;
      }

      JournalEntry entry;
      try {
        entry = poster.postPayrollRun(shopId, userId, claimed, computed.lines());
      } catch (RuntimeException e) {
        // a refused post (shop's own account on an HR code) is counted by the poster itself and
        // is not a journal failure
        if (!HrAccountingPoster.isAccountConflict(e)) {
          // exception class only: accounting messages can carry amounts
          log.error("[hr-payroll] journal post failed for run {} of shop {}: {}", runId, shopId,
              e.getClass().getSimpleName());
          metrics.increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1,
              "module", HrMetricsConstants.MODULE, "source", JournalSource.PAYROLL_RUN.name());
          metrics.increment(HrMetricsConstants.PAYROLL_FINALISE_TOTAL, 1,
              "module", HrMetricsConstants.MODULE, "outcome", "post_failed");
        }
        // the post is not atomic (no transactions): it may have saved the entry and failed after
        // it. Only when no entry exists is it safe to go back to DRAFT under the same revision;
        // a saved entry is used below (its totals are checked), and when we cannot tell, the
        // claim stays so the next call resumes after STALE_CLAIM.
        Optional<JournalEntry> saved;
        try {
          saved = poster.findRunJournal(shopId, claimed);
        } catch (RuntimeException lookupFailed) {
          throw e;
        }
        if (saved.isEmpty()) {
          releaseFinaliseClaim(shopId, runId, now);
          throw e;
        }
        entry = saved.get();
      }
      if (entry == null) {
        // a zero run posts nothing, but an entry kept from an earlier calculation must not stay
        entry = poster.findRunJournal(shopId, claimed).orElse(null);
      }
      if (entry != null && !journalMatches(entry, computed.lines())) {
        // the entry for runId:revision was posted from other inputs (a stale claim's caller, or
        // an input that is not month-locked such as a backdated advance). It cannot be posted
        // again under the same key, so reverse it and start a new revision as a draft.
        metrics.increment(HrMetricsConstants.PAYROLL_FINALISE_TOTAL, 1,
            "module", HrMetricsConstants.MODULE, "outcome", "journal_mismatch");
        resetToNewRevision(shopId, userId, claimed, now, entry);
        throw stateConflict("Payroll changed while it was being finalised, so it is back to"
            + " draft. Check it and finalise again");
      }

      PayrollRun.Totals totals = totalsOf(computed.lines());
      List<PayrollRun.JournalHistoryEntry> history = new ArrayList<>(
          claimed.getJournalHistory() != null ? claimed.getJournalHistory() : List.of());
      int revision = claimed.getRevision() != null ? claimed.getRevision() : 0;
      // a resumed finalise posts the same entry again: keep one history row per revision
      history.removeIf(h -> h.getRevision() != null && h.getRevision() == revision);
      if (entry != null) {
        history.add(new PayrollRun.JournalHistoryEntry(revision, entry.getId(), null));
      }
      Update done = new Update().set("status", PayrollRunStatus.FINALISED).unset("claimedAt")
          .set("journalEntryId", entry != null ? entry.getId() : null)
          .set("journalHistory", history).set("totals", totals).set("calculatedAt", now)
          .set("finalisedAt", now).set("finalisedByUserId", userId);
      PayrollRun finalisedRun = mongoTemplate.findAndModify(ownClaim(shopId, runId,
          PayrollRunStatus.FINALISING, now), done,
          FindAndModifyOptions.options().returnNew(true), PayrollRun.class);
      if (finalisedRun == null) {
        // a re-claimer took over after our claim went stale; it posts the same journal and
        // finishes the run, so report the run as it is now
        log.warn("[hr-payroll] finalise of run {} of shop {} lost its claim", runId, shopId);
        return withLines(shopId, find(shopId, runId));
      }
      Map<String, Object> after = new LinkedHashMap<>();
      after.put("status", PayrollRunStatus.FINALISED.name());
      after.put("revision", revision);
      after.put("employeeCount", totals.getEmployeeCount());
      after.put("journalEntryId", entry != null ? entry.getId() : null);
      auditService.record(new AuditEntry(shopId, userId, HrAuditAction.PAYROLL_FINALISED, ENTITY,
          runId, null, null, claimed.getPeriod(),
          Map.of("status", PayrollRunStatus.DRAFT.name()), after, null));
      metrics.increment(HrMetricsConstants.PAYROLL_FINALISE_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "outcome", "success");
      return withLines(shopId, finalisedRun);
    });
  }

  /**
   * A month whose last day is after today (Asia/Kolkata) cannot be finalised: its later days
   * cannot be marked yet. Without this the refusal would read as "unmarked days" while the
   * unmarked list (which leaves future days out) shows nothing to mark.
   */
  static void assertMonthOver(PayrollRun run) {
    YearMonth month = YearMonth.parse(run.getPeriod());
    if (month.atEndOfMonth().isAfter(HrDateUtils.today())) {
      throw new ValidationException("This month is not over yet. Payroll for "
          + run.getPeriod() + " can be finalised from " + HrDateUtils.format(
              month.plusMonths(1).atDay(1)));
    }
  }

  /** Unmarked days block finalise (R8.12); so does a line whose deductions exceed its pay. */
  private static void assertFinalisable(Computed computed) {
    long withUnmarked = computed.unmarkedEmployees();
    if (withUnmarked > 0) {
      throw new BaseException(ErrorCode.HR_UNMARKED_DAYS, withUnmarked
          + " employee(s) have unmarked days in this month. Mark them first");
    }
    for (PayrollLine l : computed.lines()) {
      if (l.getNetBeforeRounding().signum() < 0) {
        throw new ValidationException("The deductions of " + l.getEmployeeSnapshot().getCode()
            + " are more than the pay for the month. Change the adjustments first");
      }
    }
  }

  /**
   * Writes the final lines with {@code frozenAt} = our claim time: upserts by {@code (runId,
   * employeeId)} with no version condition (the run is ours), and removes lines of employees no
   * longer in the month.
   */
  private void writeFrozenLines(String shopId, PayrollRun run, Computed computed,
      Instant claimedAt) {
    BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED,
        PayrollLine.class);
    Set<String> keep = new HashSet<>();
    for (PayrollLine line : computed.lines()) {
      keep.add(line.getEmployeeId());
      bulk.upsert(Query.query(Criteria.where("shopId").is(shopId).and("runId").is(run.getId())
          .and("employeeId").is(line.getEmployeeId())),
          lineUpdate(line, claimedAt).set("frozenAt", claimedAt));
    }
    bulk.remove(Query.query(Criteria.where("shopId").is(shopId).and("runId").is(run.getId())
        .and("employeeId").nin(keep)));
    bulk.execute();
  }

  /**
   * Any attendance mark of the month or leave decision touching it written after the claim. Both
   * reads use existing indexes ({@code (shopId, date)}, {@code (shopId, status, fromDate)}).
   */
  private boolean changedSince(String shopId, YearMonth month, Instant claimedAt) {
    String from = HrDateUtils.format(month.atDay(1));
    String to = HrDateUtils.format(month.atEndOfMonth());
    Query days = Query.query(Criteria.where("shopId").is(shopId).and("date").gte(from).lte(to)
        .and("markedAt").gt(claimedAt));
    days.maxTimeMsec(HrQueryRunner.MONTH_MAX_TIME_MS);
    if (mongoTemplate.exists(days, AttendanceDay.class)) {
      return true;
    }
    String lowest = HrDateUtils.format(month.atDay(1)
        .minusDays(AttendanceService.LEAVE_MAX_DAYS - 1));
    Query leave = Query.query(Criteria.where("shopId").is(shopId).and("status")
        .in(LeaveRequestStatus.APPROVED, LeaveRequestStatus.CANCELLED)
        .and("fromDate").gte(lowest).lte(to).and("toDate").gte(from)
        .and("decidedAt").gt(claimedAt));
    leave.maxTimeMsec(HrQueryRunner.MONTH_MAX_TIME_MS);
    return mongoTemplate.exists(leave, LeaveRequest.class);
  }

  /** Lines first (while the run still says FINALISING, so no draft write meets a frozen line). */
  private void releaseFinaliseClaim(String shopId, String runId, Instant claimedAt) {
    try {
      mongoTemplate.updateMulti(Query.query(Criteria.where("shopId").is(shopId).and("runId")
          .is(runId).and("frozenAt").is(claimedAt)), new Update().unset("frozenAt"),
          PayrollLine.class);
      mongoTemplate.updateFirst(ownClaim(shopId, runId, PayrollRunStatus.FINALISING, claimedAt),
          new Update().set("status", PayrollRunStatus.DRAFT).unset("claimedAt"), PayrollRun.class);
    } catch (RuntimeException e) {
      // the stale-claim rule frees the run after STALE_CLAIM
      log.error("[hr-payroll] could not release the finalise claim of run {} of shop {}: {}",
          runId, shopId, e.getClass().getSimpleName());
    }
  }

  /**
   * A caller stalled past {@link #STALE_CLAIM} may have lost the run to a re-claimer; it must
   * stop before writing lines or posting, or it could overwrite the re-claimer's work.
   */
  private void assertStillClaimed(String shopId, String runId, Instant claimedAt) {
    if (!mongoTemplate.exists(ownClaim(shopId, runId, PayrollRunStatus.FINALISING, claimedAt),
        PayrollRun.class)) {
      throw stateConflict("Someone else took over finalising this payroll run");
    }
  }

  /**
   * The entry is the one these lines would post: same total debit (5700 + any 5500 debit) and
   * the same Σ net on 2400.
   */
  static boolean journalMatches(JournalEntry entry, List<PayrollLine> lines) {
    HrAccountingPoster.RunTotals t = HrAccountingPoster.runTotals(lines);
    BigDecimal debit = t.expense().add(t.rounding().max(BigDecimal.ZERO));
    BigDecimal payable = BigDecimal.ZERO;
    if (entry.getLines() != null) {
      for (JournalLine l : entry.getLines()) {
        if (SystemAccountCode.SALARY_PAYABLE.equals(l.getAccountCode())) {
          payable = payable.add(HrMoneyUtils.nz(l.getCredit()));
        }
      }
    }
    return entry.getTotalDebit() != null && entry.getTotalDebit().compareTo(debit) == 0
        && payable.compareTo(t.net()) == 0;
  }

  /**
   * Reverses an entry that does not match the frozen lines and puts the run back to DRAFT with
   * {@code revision + 1}, so the next finalise posts under a new key. Only under our claim; if
   * the reversal fails the run stays FINALISING and the stale rule hands it to the next call.
   */
  private void resetToNewRevision(String shopId, String userId, PayrollRun claimed,
      Instant claimedAt, JournalEntry stale) {
    String reversal = poster.reverseBySource(shopId, userId, JournalSource.PAYROLL_RUN,
        HrAccountingPoster.runSourceId(claimed), "Payroll " + claimed.getPeriod()
            + " changed while finalising");
    int revision = claimed.getRevision() != null ? claimed.getRevision() : 0;
    List<PayrollRun.JournalHistoryEntry> history = new ArrayList<>(
        claimed.getJournalHistory() != null ? claimed.getJournalHistory() : List.of());
    history.removeIf(h -> h.getRevision() != null && h.getRevision() == revision);
    history.add(new PayrollRun.JournalHistoryEntry(revision, stale.getId(), reversal));
    mongoTemplate.updateMulti(Query.query(Criteria.where("shopId").is(shopId).and("runId")
        .is(claimed.getId()).and("frozenAt").is(claimedAt)), new Update().unset("frozenAt"),
        PayrollLine.class);
    mongoTemplate.updateFirst(ownClaim(shopId, claimed.getId(), PayrollRunStatus.FINALISING,
        claimedAt), new Update().set("status", PayrollRunStatus.DRAFT).unset("claimedAt")
        .set("revision", revision + 1).unset("journalEntryId").set("journalHistory", history),
        PayrollRun.class);
    log.warn("[hr-payroll] run {} of shop {} reset to revision {}: its journal did not match",
        claimed.getId(), shopId, revision + 1);
  }

  // ---- reopen ----------------------------------------------------------------------------------

  /**
   * {@code POST /payroll-runs/{runId}/reopen {reason}} (R8.14, R9.6, R11.8): the latest finalised
   * run only. Claim FINALISED (or a stale REOPENING) → REOPENING, re-check the order, refuse
   * while any line is paid or being paid, reverse the journal unless already reversed, unfreeze
   * the lines, then DRAFT with {@code revision + 1}.
   */
  public PayrollRunResponse reopen(String userId, String shopId, String runId, String reason) {
    guard.require(userId, shopId, HrPermission.RUN_PAYROLL);
    String why = validator.requireReason(reason);
    return queryRunner.read("payroll_reopen", () -> {
      PayrollRun run = find(shopId, runId);
      Instant now = nowMillis();
      boolean resumable = run.getStatus() == PayrollRunStatus.FINALISED
          || (run.getStatus() == PayrollRunStatus.REOPENING && isStale(run, now));
      if (!resumable) {
        throw stateConflict(run.getStatus() == PayrollRunStatus.DRAFT
            ? "Only a finalised payroll run can be reopened"
            : "This payroll run is being finalised or reopened. Try again in a minute");
      }
      assertReopenOrder(run, runs(shopId));
      PayrollRun claimed = claim(shopId, runId, now, PayrollRunStatus.FINALISED,
          PayrollRunStatus.REOPENING);
      if (claimed == null) {
        throw stateConflict("Someone else is reopening this payroll run");
      }
      try {
        // a pay or undo call that died must not block reopen forever: settle stale PAYING /
        // UNDOING lines first (they become PAID or PENDING); the paid check below still runs
        // after our claim, so "claim first, then check the other" holds
        salaryPaymentService.finishStaleClaims(userId, shopId, runId);
        // claim first, then check: a later month claimed for finalise, or a payment that claimed
        // a line, is seen here
        assertReopenOrder(claimed, runs(shopId));
        Query paid = Query.query(Criteria.where("shopId").is(shopId).and("runId").is(runId)
            .and("paymentStatus").in(SalaryPaymentStatus.PAID, SalaryPaymentStatus.PAYING,
                SalaryPaymentStatus.UNDOING));
        paid.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
        if (mongoTemplate.exists(paid, PayrollLine.class)) {
          throw new BaseException(ErrorCode.HR_RUN_HAS_PAYMENTS,
              ErrorCode.HR_RUN_HAS_PAYMENTS.getMessage());
        }
      } catch (RuntimeException e) {
        // nothing was reversed yet: the run is still finalised
        mongoTemplate.updateFirst(ownClaim(shopId, runId, PayrollRunStatus.REOPENING, now),
            new Update().set("status", PayrollRunStatus.FINALISED).unset("claimedAt"),
            PayrollRun.class);
        throw e;
      }
      // reversing an already reversed entry throws, so only a live one is reversed. The reversal
      // is several writes (no transactions): if it fails part way the run stays REOPENING and the
      // next reopen after STALE_CLAIM finishes it (the REV:<entryId> post is idempotent); going
      // back to FINALISED here could leave a finalised run whose entry is reversed.
      String reversalId = poster.reverseBySource(shopId, userId, JournalSource.PAYROLL_RUN,
          HrAccountingPoster.runSourceId(claimed), "Payroll " + claimed.getPeriod()
              + " reopened: " + why);
      mongoTemplate.updateMulti(Query.query(Criteria.where("shopId").is(shopId).and("runId")
          .is(runId).and("frozenAt").ne(null)), new Update().unset("frozenAt"), PayrollLine.class);

      int revision = claimed.getRevision() != null ? claimed.getRevision() : 0;
      List<PayrollRun.JournalHistoryEntry> history = new ArrayList<>(
          claimed.getJournalHistory() != null ? claimed.getJournalHistory() : List.of());
      for (PayrollRun.JournalHistoryEntry h : history) {
        if (h.getRevision() != null && h.getRevision() == revision) {
          h.setReversalEntryId(reversalId);
        }
      }
      Update draft = new Update().set("status", PayrollRunStatus.DRAFT).unset("claimedAt")
          .set("revision", revision + 1).unset("journalEntryId").unset("finalisedAt")
          .unset("finalisedByUserId").set("journalHistory", history)
          .push("reopenHistory", new PayrollRun.ReopenHistoryEntry(now, userId, why));
      PayrollRun reopened = mongoTemplate.findAndModify(ownClaim(shopId, runId,
          PayrollRunStatus.REOPENING, now), draft, FindAndModifyOptions.options().returnNew(true),
          PayrollRun.class);
      if (reopened == null) {
        log.warn("[hr-payroll] reopen of run {} of shop {} lost its claim", runId, shopId);
        return withLines(shopId, find(shopId, runId));
      }
      auditService.record(new AuditEntry(shopId, userId, HrAuditAction.PAYROLL_REOPENED, ENTITY,
          runId, null, null, claimed.getPeriod(),
          Map.of("status", PayrollRunStatus.FINALISED.name(), "revision", revision),
          Map.of("status", PayrollRunStatus.DRAFT.name(), "revision", revision + 1), why));
      metrics.increment(HrMetricsConstants.PAYROLL_REOPEN_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "outcome", "success");
      return withLines(shopId, reopened);
    });
  }

  // ---- order rules (D9) ------------------------------------------------------------------------

  /** Every earlier run FINALISED and no later run past DRAFT. */
  static void assertFinaliseOrder(PayrollRun run, List<PayrollRun> runs) {
    for (PayrollRun r : runs) {
      if (r.getId() == null || r.getId().equals(run.getId())) {
        continue;
      }
      int cmp = r.getPeriod().compareTo(run.getPeriod());
      if (cmp < 0 && r.getStatus() != PayrollRunStatus.FINALISED) {
        throw new BaseException(ErrorCode.HR_RUN_ORDER,
            "Finalise payroll for " + r.getPeriod() + " first");
      }
      if (cmp > 0 && r.getStatus() != PayrollRunStatus.DRAFT) {
        throw new BaseException(ErrorCode.HR_RUN_ORDER, "Payroll for " + r.getPeriod()
            + " is already finalised. Months are finalised oldest first");
      }
    }
  }

  /** Only the latest finalised run: no later run past DRAFT. */
  static void assertReopenOrder(PayrollRun run, List<PayrollRun> runs) {
    for (PayrollRun r : runs) {
      if (r.getId() != null && !r.getId().equals(run.getId())
          && r.getPeriod().compareTo(run.getPeriod()) > 0
          && r.getStatus() != PayrollRunStatus.DRAFT) {
        throw new BaseException(ErrorCode.HR_RUN_ORDER,
            "Reopen payroll for " + r.getPeriod() + " first. Months are reopened newest first");
      }
    }
  }

  // ---- calculation -----------------------------------------------------------------------------

  /** Lines of one calculation, the lines stored before it, and who still has unmarked days. */
  record Computed(List<PayrollLine> lines, Map<String, PayrollLine> existing,
      long unmarkedEmployees) {}

  private Computed compute(String shopId, PayrollRun run, List<String> finalisedRunIds) {
    PayrollInputReader.Snapshot snapshot = inputReader.read(shopId,
        YearMonth.parse(run.getPeriod()), run.getId(), finalisedRunIds, null, true);
    List<PayrollLine> lines = new ArrayList<>();
    long unmarked = 0;
    for (PayrollInput.EmployeeInput in : snapshot.input().employees()) {
      PayrollCalculator.Result result = PayrollCalculator.calculate(snapshot.input(), in);
      PayrollLine line = result.line();
      line.setShopId(shopId);
      line.setRunId(run.getId());
      PayrollLine old = snapshot.existingLines().get(in.employee().getId());
      if (old != null) {
        line.setId(old.getId());
      }
      lines.add(line);
      if (!result.unmarkedDates().isEmpty()) {
        unmarked++;
      }
    }
    lines.sort(Comparator.comparing(l -> String.valueOf(l.getEmployeeSnapshot().getCode())));
    return new Computed(lines, snapshot.existingLines(), unmarked);
  }

  /**
   * The computed fields of a line ({@code $set}) and its keys on insert. Payment fields are never
   * touched here: a draft has no payments and a finalised line's payments belong to task 9.
   */
  private static Update lineUpdate(PayrollLine line, Instant now) {
    return new Update()
        .set("period", line.getPeriod())
        .set("employeeSnapshot", line.getEmployeeSnapshot())
        .set("dayCounts", line.getDayCounts())
        .set("overtimeMinutes", line.getOvertimeMinutes())
        .set("earnings", line.getEarnings())
        .set("deductions", line.getDeductions())
        .set("manualItems", line.getManualItems())
        .set("advanceOverride", line.getAdvanceOverride())
        .set("advanceRecoveries", line.getAdvanceRecoveries())
        .set("advanceOutstandingAfter", line.getAdvanceOutstandingAfter())
        .set("gross", line.getGross())
        .set("totalDeductions", line.getTotalDeductions())
        .set("netBeforeRounding", line.getNetBeforeRounding())
        .set("rounding", line.getRounding())
        .set("net", line.getNet())
        .set("warnings", line.getWarnings())
        .set("updatedAt", now)
        .setOnInsert("shopId", line.getShopId())
        .setOnInsert("runId", line.getRunId())
        .setOnInsert("employeeId", line.getEmployeeId())
        .setOnInsert("paymentStatus", SalaryPaymentStatus.PENDING)
        .setOnInsert("paymentSeq", 0);
  }

  static PayrollRun.Totals totalsOf(List<PayrollLine> lines) {
    BigDecimal gross = BigDecimal.ZERO;
    BigDecimal deductions = BigDecimal.ZERO;
    BigDecimal rounding = BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    for (PayrollLine l : lines) {
      gross = gross.add(HrMoneyUtils.nz(l.getGross()));
      deductions = deductions.add(HrMoneyUtils.nz(l.getTotalDeductions()));
      rounding = rounding.add(HrMoneyUtils.nz(l.getRounding()));
      net = net.add(HrMoneyUtils.nz(l.getNet()));
    }
    BigDecimal recovered = HrAccountingPoster.runTotals(lines).recovered();
    return new PayrollRun.Totals(gross, deductions, recovered, rounding, net, lines.size());
  }

  private void updateDraftTotals(String shopId, PayrollRun run, PayrollRun.Totals totals) {
    Query draft = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(run.getId())
        .and("status").is(PayrollRunStatus.DRAFT));
    if (mongoTemplate.updateFirst(draft, new Update().set("totals", totals)
        .set("calculatedAt", nowMillis()), PayrollRun.class).getMatchedCount() != 1) {
      throw stateConflict("This payroll run is being finalised. Try again in a minute");
    }
  }

  private void recordCalc(String operation, long startedNanos) {
    double seconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
    metrics.increment(HrMetricsConstants.PAYROLL_CALC_TOTAL, 1,
        "module", HrMetricsConstants.MODULE, "operation", operation);
    metrics.increment(HrMetricsConstants.PAYROLL_CALC_SECONDS_TOTAL, seconds,
        "module", HrMetricsConstants.MODULE, "operation", operation);
  }

  // ---- claims ----------------------------------------------------------------------------------

  /**
   * {@code from} → {@code to} with {@code claimedAt = now}, or take over a {@code to} claim that
   * is stale (older than {@link #STALE_CLAIM}, or without a time). Returns the claimed run, or
   * null when someone else holds it.
   */
  private PayrollRun claim(String shopId, String runId, Instant now, PayrollRunStatus from,
      PayrollRunStatus to) {
    Instant staleBefore = now.minus(STALE_CLAIM);
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(runId)
        .orOperator(Criteria.where("status").is(from),
            Criteria.where("status").is(to).and("claimedAt").not().gte(staleBefore)));
    return mongoTemplate.findAndModify(query,
        new Update().set("status", to).set("claimedAt", now),
        FindAndModifyOptions.options().returnNew(true), PayrollRun.class);
  }

  /** Matches the run only while it is still in {@code status} under our claim time. */
  private static Query ownClaim(String shopId, String runId, PayrollRunStatus status,
      Instant claimedAt) {
    return Query.query(Criteria.where("shopId").is(shopId).and("_id").is(runId)
        .and("status").is(status).and("claimedAt").is(claimedAt));
  }

  static boolean isStale(PayrollRun run, Instant now) {
    return run.getClaimedAt() == null || run.getClaimedAt().isBefore(now.minus(STALE_CLAIM));
  }

  /** Mongo keeps milliseconds; claim times are compared for equality, so ours must match. */
  private static Instant nowMillis() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private PayrollRun find(String shopId, String runId) {
    return runRepository.findByShopIdAndId(shopId, runId)
        .orElseThrow(() -> new ResourceNotFoundException("PayrollRun", "id", runId));
  }

  /** All runs of the shop, id/period/status only: order rules and finalised ids. */
  private List<PayrollRun> runs(String shopId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId));
    query.fields().include("_id", "period", "status");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.find(query, PayrollRun.class);
  }

  private static List<String> finalisedIds(List<PayrollRun> runs) {
    return runs.stream().filter(r -> r.getStatus() == PayrollRunStatus.FINALISED)
        .map(PayrollRun::getId).toList();
  }

  private static void requireDraft(PayrollRun run) {
    if (run.getStatus() != PayrollRunStatus.DRAFT) {
      throw stateConflict("Only a draft payroll run can be changed. Reopen it first");
    }
  }

  private PayrollRunResponse withLines(String shopId, PayrollRun run) {
    return response(run, lineRepository.findByShopIdAndRunId(shopId, run.getId()));
  }

  private PayrollRunResponse response(PayrollRun run, List<PayrollLine> lines) {
    PayrollRunResponse response = mapper.toResponse(run);
    List<PayrollLine> sorted = new ArrayList<>(lines);
    sorted.sort(Comparator.comparing(l -> l.getEmployeeSnapshot() != null
        ? String.valueOf(l.getEmployeeSnapshot().getCode()) : ""));
    response.setLines(sorted.stream().map(mapper::toResponse).toList());
    return response;
  }

  private static boolean allDuplicates(BulkOperationException e) {
    for (BulkWriteError error : e.getErrors()) {
      if (error.getCode() != DUPLICATE_KEY) {
        return false;
      }
    }
    return true;
  }

  private static BaseException stateConflict(String message) {
    return new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT, message);
  }

  private static BaseException concurrentUpdate() {
    return new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
        "Someone else changed this payroll at the same time. Reload and try again");
  }
}
