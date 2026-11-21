package com.inventory.hr.service;

import com.inventory.accounting.api.JournalEntryView;
import com.inventory.accounting.api.JournalSource;
import com.inventory.accounting.api.SystemAccountCode;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ResourceNotFoundException;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.Employee;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.AdvanceStatus;
import com.inventory.hr.domain.model.enums.HrAuditAction;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.domain.model.enums.HrPermission;
import com.inventory.hr.domain.model.enums.PayrollRunStatus;
import com.inventory.hr.domain.repository.AdvanceRepository;
import com.inventory.hr.mapper.HrPayrollMapper;
import com.inventory.hr.rest.dto.request.RecordAdvanceRequest;
import com.inventory.hr.rest.dto.response.AdvanceResponse;
import com.inventory.hr.rest.dto.response.AdvanceStatementResponse;
import com.inventory.hr.service.HrAuditService.AuditEntry;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.HrMoneyUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.hr.validation.AdvanceValidator;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Salary advances (R7, R11.2, R12.3). Outstanding is derived, never stored (D5): amount − Σ
 * recoveries of the advance on lines of FINALISED payroll runs, one aggregation per request.
 *
 * <p>Accounting without transactions: an advance is inserted first and then posted ({@code
 * STAFF_ADVANCE}, sourceId = advance id). If the post fails the advance keeps {@code
 * journalEntryId = null}; the next record, list or cancel for that employee posts it again, and the
 * source key makes that idempotent. Cancel reverses the entry the same way ({@code
 * journalReversed}).
 */
@Slf4j
@Service
public class AdvanceService {

  static final String ENTITY = "ADVANCE";
  /** Longest list one call returns (newest first). */
  static final int LIST_LIMIT = 500;

  @Autowired private MongoTemplate mongoTemplate;

  @Autowired private AdvanceRepository advanceRepository;

  @Autowired private HrPermissionGuard guard;

  @Autowired private AdvanceValidator validator;

  @Autowired private HrAccountingPoster poster;

  @Autowired private HrAuditService auditService;

  @Autowired private HrPayrollMapper mapper;

  @Autowired private HrQueryRunner queryRunner;

  @Autowired private MetricsWrapper metrics;

  // ---- list ------------------------------------------------------------------------------------

  /** {@code GET /advances[?employeeId=]}, newest first (MANAGE_ADVANCES). */
  public List<AdvanceResponse> list(String userId, String shopId, String employeeId) {
    guard.require(userId, shopId, HrPermission.MANAGE_ADVANCES);
    return queryRunner.read("advances_list", () -> {
      Criteria c = Criteria.where("shopId").is(shopId);
      if (StringUtils.hasText(employeeId)) {
        c = c.and("employeeId").is(employeeId);
      }
      Query query = Query.query(c).with(Sort.by(Sort.Direction.DESC, "date", "createdAt"))
          .limit(LIST_LIMIT);
      query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<Advance> advances = mongoTemplate.find(query, Advance.class);
      if (advances.isEmpty()) {
        return List.of();
      }
      Map<String, Employee> employees = employeesById(shopId,
          advances.stream().map(Advance::getEmployeeId).toList());
      repairJournals(shopId, userId, advances, employees);
      Map<String, BigDecimal> recovered = recoveredByAdvance(shopId,
          advances.stream().map(Advance::getId).toList(), finalisedRunIds(shopId));
      List<AdvanceResponse> out = new ArrayList<>(advances.size());
      for (Advance a : advances) {
        out.add(response(a, employees.get(a.getEmployeeId()), recovered));
      }
      return out;
    });
  }

  // ---- record ----------------------------------------------------------------------------------

  /** {@code POST /advances}: insert, then post Dr 1250 / Cr cash or bank (R7.1, R7.6, R11.2). */
  public AdvanceResponse record(String userId, String shopId, RecordAdvanceRequest request) {
    guard.require(userId, shopId, HrPermission.MANAGE_ADVANCES);
    HrPaymentMethod paidBy = validator.validate(request, HrDateUtils.today());
    Employee employee = queryRunner.read("advances_record",
        () -> employee(shopId, request.getEmployeeId()));
    if (!DayStatusResolver.isEmployed(HrDateUtils.parseDate(request.getDate()), employee)) {
      throw new BaseException(ErrorCode.HR_EMPLOYEE_NOT_EMPLOYED,
          employee.getCode() + " was not employed on " + request.getDate());
    }
    // refuse before saving: an advance whose journal can never post would only sit "pending"
    poster.assertHrAccounts(shopId, List.of(SystemAccountCode.STAFF_ADVANCES));

    Advance advance = new Advance();
    advance.setShopId(shopId);
    advance.setEmployeeId(employee.getId());
    advance.setDate(request.getDate());
    advance.setAmount(request.getAmount());
    advance.setMonthlyDeduction(request.getMonthlyDeduction());
    advance.setPaidBy(paidBy);
    advance.setNote(StringUtils.hasText(request.getNote()) ? request.getNote().trim() : null);
    advance.setStatus(AdvanceStatus.ACTIVE);
    advance.setCreatedAt(Instant.now());
    advance.setCreatedByUserId(userId);
    Advance saved = advanceRepository.insert(advance);

    Map<String, Object> after = new LinkedHashMap<>();
    after.put("date", saved.getDate());
    after.put("paidBy", saved.getPaidBy().name());
    auditService.record(new AuditEntry(shopId, userId, HrAuditAction.ADVANCE_RECORDED, ENTITY,
        saved.getId(), saved.getEmployeeId(), saved.getDate(), null, null, after, null));

    // posts this advance and any of the employee's earlier ones still pending; the list is read
    // back from the database, so an advance cancelled in the meantime is not posted
    List<Advance> pending = queryRunner.read("advances_record",
        () -> pendingJournals(shopId, employee.getId()));
    repairJournals(shopId, userId, pending, Map.of(employee.getId(), employee));
    pending.stream().filter(a -> a.getId().equals(saved.getId())).findFirst()
        .ifPresent(a -> saved.setJournalEntryId(a.getJournalEntryId()));
    return response(saved, employee, Map.of());
  }

  // ---- cancel ----------------------------------------------------------------------------------

  /**
   * {@code POST /advances/{id}/cancel} (R7.1): only while nothing was recovered; reverses the
   * STAFF_ADVANCE journal.
   *
   * <p>Race with payroll finalise, without transactions: cancel claims first (ACTIVE → CANCELLED)
   * and then checks the runs; finalise claims its run (→ FINALISING) and then reads the ACTIVE
   * advances. So either this check sees the FINALISING run and backs off, or the finalise read
   * happens after our claim and leaves the cancelled advance out.
   */
  public AdvanceResponse cancel(String userId, String shopId, String advanceId, String reason) {
    guard.require(userId, shopId, HrPermission.MANAGE_ADVANCES);
    String why = validator.requireReason(reason);
    return queryRunner.read("advances_cancel", () -> {
      Advance advance = advanceRepository.findByShopIdAndId(shopId, advanceId)
          .orElseThrow(() -> new ResourceNotFoundException("Advance", "id", advanceId));
      Employee employee = employee(shopId, advance.getEmployeeId());
      if (advance.getStatus() == AdvanceStatus.CANCELLED) {
        // a repeat call: finish a reversal an earlier call could not do, then answer as before
        repairJournals(shopId, userId, new ArrayList<>(List.of(advance)),
            Map.of(employee.getId(), employee));
        return response(advance, employee, Map.of());
      }
      // claim; Mongo keeps milliseconds, so the stored instant must equal ours exactly
      Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
      Query claim = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(advanceId)
          .and("status").is(AdvanceStatus.ACTIVE));
      boolean claimed = mongoTemplate.updateFirst(claim, new Update()
          .set("status", AdvanceStatus.CANCELLED).set("cancelledAt", now)
          .set("cancelledByUserId", userId).set("cancelReason", why), Advance.class)
          .getModifiedCount() == 1;
      if (!claimed) {
        throw new BaseException(ErrorCode.HR_CONCURRENT_UPDATE,
            ErrorCode.HR_CONCURRENT_UPDATE.getMessage());
      }
      List<PayrollRun> runs = runs(shopId);
      BaseException blocked = null;
      if (runs.stream().anyMatch(r -> r.getStatus() == PayrollRunStatus.FINALISING
          || r.getStatus() == PayrollRunStatus.REOPENING)) {
        blocked = new BaseException(ErrorCode.HR_RUN_STATE_CONFLICT,
            "Payroll is being finalised or reopened. Try again in a minute");
      } else {
        // recoveries in any run that is not a draft: a FINALISED run, or one that was claimed
        // after our read above
        List<String> nonDraft = runs.stream().filter(r -> r.getStatus() != PayrollRunStatus.DRAFT)
            .map(PayrollRun::getId).toList();
        if (recoveredByAdvance(shopId, List.of(advanceId), nonDraft)
            .getOrDefault(advanceId, BigDecimal.ZERO).signum() > 0) {
          blocked = new BaseException(ErrorCode.HR_ADVANCE_HAS_RECOVERIES,
              ErrorCode.HR_ADVANCE_HAS_RECOVERIES.getMessage());
        }
      }
      if (blocked != null) {
        // give the claim back; only ours (same cancelledAt) is undone
        mongoTemplate.updateFirst(Query.query(Criteria.where("shopId").is(shopId).and("_id")
            .is(advanceId).and("status").is(AdvanceStatus.CANCELLED).and("cancelledAt").is(now)),
            new Update().set("status", AdvanceStatus.ACTIVE).unset("cancelledAt")
                .unset("cancelledByUserId").unset("cancelReason"), Advance.class);
        throw blocked;
      }
      advance.setStatus(AdvanceStatus.CANCELLED);
      advance.setCancelledAt(now);
      advance.setCancelledByUserId(userId);
      advance.setCancelReason(why);
      auditService.record(new AuditEntry(shopId, userId, HrAuditAction.ADVANCE_CANCELLED, ENTITY,
          advanceId, advance.getEmployeeId(), advance.getDate(), null,
          Map.of("status", AdvanceStatus.ACTIVE.name()),
          Map.of("status", AdvanceStatus.CANCELLED.name()), why));
      repairJournals(shopId, userId, new ArrayList<>(List.of(advance)),
          Map.of(employee.getId(), employee));
      return response(advance, employee, Map.of());
    });
  }

  // ---- statement -------------------------------------------------------------------------------

  /**
   * {@code GET /employees/{id}/advance-statement} (R7.5, R12.3): advances given and recovered by
   * finalised payroll, oldest first, with the running balance. A cancelled advance is listed but
   * adds nothing (it was never recovered).
   */
  public AdvanceStatementResponse statement(String userId, String shopId, String employeeId) {
    guard.require(userId, shopId, HrPermission.MANAGE_ADVANCES);
    return queryRunner.read("advances_statement", () -> {
      Employee employee = employee(shopId, employeeId);
      Query advancesQuery = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
          .is(employeeId));
      advancesQuery.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
      List<Advance> advances = mongoTemplate.find(advancesQuery, Advance.class);
      List<String> finalised = finalisedRunIds(shopId);
      List<PayrollLine> lines = List.of();
      if (!advances.isEmpty() && !finalised.isEmpty()) {
        Query linesQuery = Query.query(Criteria.where("shopId").is(shopId).and("employeeId")
            .is(employeeId).and("runId").in(finalised).and("advanceRecoveries.0").exists(true));
        linesQuery.fields().include("period", "fromDate", "toDate", "advanceRecoveries");
        linesQuery.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
        lines = mongoTemplate.find(linesQuery, PayrollLine.class);
      }
      return buildStatement(employee, advances, lines);
    });
  }

  /** Pure: rows sorted by date (given before recovered on the same date), running balance. */
  static AdvanceStatementResponse buildStatement(Employee employee, List<Advance> advances,
      List<PayrollLine> lines) {
    List<AdvanceStatementResponse.Row> rows = new ArrayList<>();
    for (Advance a : advances) {
      rows.add(new AdvanceStatementResponse.Row(a.getDate(), AdvanceStatementResponse.Kind.GIVEN,
          a.getId(), null, a.getAmount(), null, a.getStatus() == AdvanceStatus.CANCELLED));
    }
    for (PayrollLine l : lines) {
      // recovered on the last day of the line's salary month (PayCycle)
      String date = PayCycle.stored(l.getPeriod(), l.getFromDate(), l.getToDate()).toText();
      for (PayrollLine.AdvanceRecovery r : l.getAdvanceRecoveries()) {
        rows.add(new AdvanceStatementResponse.Row(date, AdvanceStatementResponse.Kind.RECOVERED,
            r.getAdvanceId(), l.getPeriod(), r.getAmount(), null, false));
      }
    }
    rows.sort(Comparator.comparing(AdvanceStatementResponse.Row::getDate)
        .thenComparing(AdvanceStatementResponse.Row::getKind)
        .thenComparing(r -> r.getAdvanceId() != null ? r.getAdvanceId() : ""));
    BigDecimal balance = BigDecimal.ZERO;
    for (AdvanceStatementResponse.Row row : rows) {
      if (row.getKind() == AdvanceStatementResponse.Kind.GIVEN) {
        if (!row.isCancelled()) {
          balance = balance.add(HrMoneyUtils.nz(row.getAmount()));
        }
      } else {
        balance = balance.subtract(HrMoneyUtils.nz(row.getAmount()));
      }
      row.setBalance(balance);
    }
    AdvanceStatementResponse response = new AdvanceStatementResponse();
    response.setEmployeeId(employee.getId());
    response.setEmployeeCode(employee.getCode());
    response.setEmployeeName(employee.getName());
    response.setRows(rows);
    response.setOutstanding(balance);
    return response;
  }

  // ---- shared reads (also used by payroll) -----------------------------------------------------

  /**
   * Σ recovered per advance on lines of the given runs: one aggregation, bounded by the {@code
   * (runId, employeeId)} index through {@code runId $in}. Pass FINALISED run ids for outstanding.
   */
  public Map<String, BigDecimal> recoveredByAdvance(String shopId, Collection<String> advanceIds,
      Collection<String> runIds) {
    Map<String, BigDecimal> recovered = new HashMap<>();
    if (advanceIds == null || advanceIds.isEmpty() || runIds == null || runIds.isEmpty()) {
      return recovered;
    }
    Set<String> ids = new TreeSet<>(advanceIds);
    Aggregation aggregation = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("shopId").is(shopId).and("runId").in(runIds)
            .and("advanceRecoveries.advanceId").in(ids)),
        Aggregation.unwind("advanceRecoveries"),
        Aggregation.match(Criteria.where("advanceRecoveries.advanceId").in(ids)),
        Aggregation.group("advanceRecoveries.advanceId").sum("advanceRecoveries.amount")
            .as("recovered"))
        .withOptions(AggregationOptions.builder()
            .maxTime(Duration.ofMillis(HrQueryRunner.MONTH_MAX_TIME_MS)).build());
    for (Document d : mongoTemplate.aggregate(aggregation, PayrollLine.class, Document.class)
        .getMappedResults()) {
      recovered.put(String.valueOf(d.get("_id")), HrMoneyUtils.toBigDecimal(d.get("recovered")));
    }
    return recovered;
  }

  /** Ids of the shop's FINALISED runs (the runs whose recoveries count, D3/D9). */
  List<String> finalisedRunIds(String shopId) {
    return runs(shopId).stream().filter(r -> r.getStatus() == PayrollRunStatus.FINALISED)
        .map(PayrollRun::getId).toList();
  }

  private List<PayrollRun> runs(String shopId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId));
    query.fields().include("_id", "period", "status");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.find(query, PayrollRun.class);
  }

  // ---- journals --------------------------------------------------------------------------------

  /**
   * Finishes the accounts work of the given advances: posts ACTIVE ones still without a journal,
   * reverses CANCELLED ones not yet reversed. A failure is logged (ids only) and counted; the
   * advance stays pending for the next call, so the user's action never fails on it.
   */
  private void repairJournals(String shopId, String userId, List<Advance> advances,
      Map<String, Employee> employees) {
    for (Advance a : advances) {
      try {
        if (a.getStatus() == AdvanceStatus.ACTIVE && a.getJournalEntryId() == null) {
          Employee e = employees.get(a.getEmployeeId());
          JournalEntryView entry = poster.postAdvance(shopId, userId, a, e != null ? e.getCode() : null);
          if (entry != null) {
            boolean stillActive = mongoTemplate.updateFirst(Query.query(Criteria.where("shopId")
                .is(shopId).and("_id").is(a.getId()).and("status").is(AdvanceStatus.ACTIVE)
                .and("journalEntryId").is(null)),
                new Update().set("journalEntryId", entry.id()), Advance.class)
                .getMatchedCount() == 1;
            a.setJournalEntryId(entry.id());
            if (!stillActive && cancelledSince(shopId, a.getId())) {
              // cancelled between our read and the post, and that cancel found nothing to
              // reverse yet: take the entry back now
              poster.reverseBySource(shopId, userId, JournalSource.STAFF_ADVANCE, a.getId(),
                  "Advance cancelled");
              mongoTemplate.updateFirst(Query.query(Criteria.where("shopId").is(shopId)
                  .and("_id").is(a.getId())), new Update().set("journalEntryId", entry.id())
                  .set("journalReversed", true), Advance.class);
            }
          }
        } else if (a.getStatus() == AdvanceStatus.CANCELLED
            && !Boolean.TRUE.equals(a.getJournalReversed())) {
          poster.reverseBySource(shopId, userId, JournalSource.STAFF_ADVANCE, a.getId(),
              "Advance cancelled");
          mongoTemplate.updateFirst(Query.query(Criteria.where("shopId").is(shopId).and("_id")
              .is(a.getId())), new Update().set("journalReversed", true), Advance.class);
          a.setJournalReversed(true);
        }
      } catch (RuntimeException e) {
        if (HrAccountingPoster.isAccountConflict(e)) {
          // the poster logged and counted it; the advance stays pending until 1250 is renamed
          continue;
        }
        // exception class only: accounting messages can carry amounts
        log.error("[hr-advance] journal for advance {} of shop {} not done yet: {}", a.getId(),
            shopId, e.getClass().getSimpleName());
        metrics.increment(HrMetricsConstants.JOURNAL_POST_FAILURES_TOTAL, 1,
            "module", HrMetricsConstants.MODULE, "source", JournalSource.STAFF_ADVANCE.name());
      }
    }
  }

  /** The advance is CANCELLED now (one read by id). */
  private boolean cancelledSince(String shopId, String advanceId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(advanceId)
        .and("status").is(AdvanceStatus.CANCELLED));
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return mongoTemplate.exists(query, Advance.class);
  }

  private List<Advance> pendingJournals(String shopId, String employeeId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("employeeId").is(employeeId)
        .and("status").is(AdvanceStatus.ACTIVE).and("journalEntryId").is(null));
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    return new ArrayList<>(mongoTemplate.find(query, Advance.class));
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private Employee employee(String shopId, String employeeId) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id").is(employeeId));
    query.fields().include("code", "name", "joinDate", "exitDate", "createdAt");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Employee e = mongoTemplate.findOne(query, Employee.class);
    if (e == null) {
      throw new ResourceNotFoundException("Employee", "id", employeeId);
    }
    return e;
  }

  private Map<String, Employee> employeesById(String shopId, Collection<String> ids) {
    Query query = Query.query(Criteria.where("shopId").is(shopId).and("_id")
        .in(new TreeSet<>(ids)));
    query.fields().include("code", "name");
    query.maxTimeMsec(HrQueryRunner.SCREEN_MAX_TIME_MS);
    Map<String, Employee> byId = new HashMap<>();
    for (Employee e : mongoTemplate.find(query, Employee.class)) {
      byId.put(e.getId(), e);
    }
    return byId;
  }

  private AdvanceResponse response(Advance a, Employee e, Map<String, BigDecimal> recovered) {
    AdvanceResponse r = mapper.toResponse(a);
    if (e != null) {
      r.setEmployeeCode(e.getCode());
      r.setEmployeeName(e.getName());
    }
    BigDecimal got = recovered.getOrDefault(a.getId(), BigDecimal.ZERO);
    r.setRecovered(got);
    // a cancelled advance was never recovered and is owed no more
    r.setOutstanding(a.getStatus() == AdvanceStatus.CANCELLED ? BigDecimal.ZERO
        : HrMoneyUtils.nz(a.getAmount()).subtract(got).max(BigDecimal.ZERO));
    r.setJournalPosted(a.getJournalEntryId() != null);
    return r;
  }

  /** Outstanding for payroll: amount − recovered, never below zero. */
  static BigDecimal outstanding(Advance a, Map<String, BigDecimal> recovered) {
    return HrMoneyUtils.nz(a.getAmount())
        .subtract(recovered.getOrDefault(a.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
  }
}
