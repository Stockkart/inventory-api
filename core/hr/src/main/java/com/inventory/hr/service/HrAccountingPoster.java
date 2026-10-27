package com.inventory.hr.service;

import com.inventory.accounting.api.AccountingFacade;
import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.api.PostJournalRequest;
import com.inventory.accounting.api.JournalEntryView;
import com.inventory.accounting.api.JournalSource;
import com.inventory.accounting.api.JournalStatus;
import com.inventory.accounting.api.PartyType;
import com.inventory.accounting.api.SystemAccountCode;
import com.inventory.common.constants.ErrorCode;
import com.inventory.common.exception.BaseException;
import com.inventory.hr.domain.model.Advance;
import com.inventory.hr.domain.model.PayrollLine;
import com.inventory.hr.domain.model.PayrollRun;
import com.inventory.hr.domain.model.enums.HrPaymentMethod;
import com.inventory.hr.utils.HrDateUtils;
import com.inventory.hr.utils.PayCycle;
import com.inventory.hr.utils.constants.HrMetricsConstants;
import com.inventory.metrics.MetricsWrapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The only HR class that calls {@link AccountingFacade} (design "Accounting integration", R11).
 * Every entry is keyed by {@code (sourceType, sourceId)}, so posting the same event again returns
 * the entry already posted. Lines carry {@code partyType = SHOP} and a memo of the employee code or
 * the run period only: never names, bank details or anything else personal.
 *
 * <pre>
 * Payroll run   PAYROLL_RUN    runId:revision   Dr 5700 Σ(netBeforeRounding + recovered)
 *                                               Dr 5500 Σ rounding (&gt; 0) | Cr 5500 (&lt; 0)
 *                                               Cr 2400 Σ net, Cr 1250 Σ recovered
 * Advance       STAFF_ADVANCE  advanceId        Dr 1250, Cr 1100 cash | 1110 bank (BANK, UPI)
 * Pay batch     SALARY_PAYMENT batchId          Dr 2400, Cr 1100 | 1110
 * Undo a line   SALARY_PAYMENT undo:line:seq    Dr 1100 | 1110, Cr 2400
 * </pre>
 *
 * A zero-total event posts nothing and returns null (the posting engine refuses fewer than two
 * effective lines).
 */
@Slf4j
@Service
public class HrAccountingPoster {

  static final String UNDO_PREFIX = "undo:";

  /**
   * The codes HR introduced. The seeder skips a code a shop already uses, so on these a shop may
   * hold an unrelated account of its own; 1100, 1110 and 5500 are seeded with every shop's
   * chart and other modules already post to them, so they are not checked here.
   */
  static final Set<String> HR_SYSTEM_CODES = Set.of(SystemAccountCode.STAFF_ADVANCES,
      SystemAccountCode.SALARY_PAYABLE, SystemAccountCode.SALARY_EXPENSE);

  @Autowired private AccountingFacade accountingFacade;

  @Autowired private MetricsWrapper metrics;

  // ---- payroll run -----------------------------------------------------------------------------

  /** {@code runId:revision}; a revision is never re-used, so a reopened run posts afresh. */
  public static String runSourceId(PayrollRun run) {
    return run.getId() + ":" + revisionOf(run);
  }

  /**
   * Posts the finalise journal of the run's current revision, dated the last day of its period.
   *
   * @return the entry (the existing one when already posted), or null for a zero run
   */
  public JournalEntryView postPayrollRun(String shopId, String userId, PayrollRun run,
      Collection<PayrollLine> lines) {
    RunTotals t = runTotals(lines);
    if (t.expense().signum() == 0 && t.net().signum() == 0) {
      return null;
    }
    String memo = "Payroll " + run.getPeriod();
    List<PostJournalLine> out = new ArrayList<>();
    out.add(debit(SystemAccountCode.SALARY_EXPENSE, t.expense(), memo));
    // the net-pay rounding goes to Round-off Expense: a debit when net pay was rounded up, a
    // credit when it was rounded down (decision 3); with it the entry balances exactly
    if (t.rounding().signum() > 0) {
      out.add(debit(SystemAccountCode.ROUND_OFF_EXPENSE, t.rounding(), memo));
    } else if (t.rounding().signum() < 0) {
      out.add(credit(SystemAccountCode.ROUND_OFF_EXPENSE, t.rounding().negate(), memo));
    }
    if (t.net().signum() > 0) {
      out.add(credit(SystemAccountCode.SALARY_PAYABLE, t.net(), memo));
    }
    if (t.recovered().signum() > 0) {
      out.add(credit(SystemAccountCode.STAFF_ADVANCES, t.recovered(), memo));
    }
    PostJournalRequest req = request(JournalSource.PAYROLL_RUN, runSourceId(run),
        // dated the last day of the salary month (PayCycle), as stored on the run
        PayCycle.stored(run.getPeriod(), run.getFromDate(), run.getToDate()).to(),
        "Payroll " + run.getPeriod() + " (revision " + revisionOf(run) + ")", out);
    return post(shopId, userId, req);
  }

  /** Sums of a run's lines as the journal uses them (also the run's totals). */
  public static RunTotals runTotals(Collection<PayrollLine> lines) {
    BigDecimal nbr = BigDecimal.ZERO;
    BigDecimal recovered = BigDecimal.ZERO;
    BigDecimal rounding = BigDecimal.ZERO;
    BigDecimal net = BigDecimal.ZERO;
    for (PayrollLine l : lines) {
      nbr = nbr.add(nz(l.getNetBeforeRounding()));
      recovered = recovered.add(recoveredOn(l));
      rounding = rounding.add(nz(l.getRounding()));
      net = net.add(nz(l.getNet()));
    }
    // salary expense = pay before the advance deduction and before rupee rounding (R11.1)
    return new RunTotals(nbr.add(recovered), recovered, rounding, net);
  }

  /** Σ advance recovered on one line. */
  public static BigDecimal recoveredOn(PayrollLine line) {
    BigDecimal sum = BigDecimal.ZERO;
    if (line.getAdvanceRecoveries() != null) {
      for (PayrollLine.AdvanceRecovery r : line.getAdvanceRecoveries()) {
        sum = sum.add(nz(r.getAmount()));
      }
    }
    return sum;
  }

  /** expense = Σ(netBeforeRounding + recovered); expense + rounding = net + recovered. */
  public record RunTotals(BigDecimal expense, BigDecimal recovered, BigDecimal rounding,
      BigDecimal net) {}

  // ---- advances --------------------------------------------------------------------------------

  /** Dr 1250 / Cr cash or bank on the advance date; null when the amount is zero. */
  public JournalEntryView postAdvance(String shopId, String userId, Advance advance,
      String employeeCode) {
    BigDecimal amount = nz(advance.getAmount());
    if (amount.signum() <= 0) {
      return null;
    }
    String memo = memo(employeeCode);
    List<PostJournalLine> out = List.of(
        debit(SystemAccountCode.STAFF_ADVANCES, amount, memo),
        credit(cashOrBank(advance.getPaidBy()), amount, memo));
    PostJournalRequest req = request(JournalSource.STAFF_ADVANCE, advance.getId(),
        HrDateUtils.parseDate(advance.getDate()), "Staff advance · " + memo, out);
    return post(shopId, userId, req);
  }

  // ---- salary payments (used by task 9.1) ------------------------------------------------------

  /**
   * One journal for one pay call (decision 6): Dr 2400 / Cr cash or bank, Σ net of the batch.
   *
   * @return the entry (existing on retry), or null when the batch nets to zero
   */
  public JournalEntryView postPaymentBatch(String shopId, String userId, String batchId,
      Collection<PayrollLine> lines, HrPaymentMethod method, LocalDate date) {
    BigDecimal total = BigDecimal.ZERO;
    String period = null;
    for (PayrollLine l : lines) {
      total = total.add(nz(l.getNet()));
      period = period != null ? period : l.getPeriod();
    }
    if (total.signum() <= 0) {
      return null;
    }
    String memo = "Salary " + (period != null ? period : "");
    List<PostJournalLine> out = List.of(
        debit(SystemAccountCode.SALARY_PAYABLE, total, memo.trim()),
        credit(cashOrBank(method), total, memo.trim()));
    PostJournalRequest req = request(JournalSource.SALARY_PAYMENT, batchId, date,
        "Salary paid · " + lines.size() + " employee(s)", out);
    return post(shopId, userId, req);
  }

  /** {@code undo:<lineId>:<paymentSeq>}: one compensating entry per undone payment. */
  public static String undoSourceId(String lineId, int paymentSeq) {
    return UNDO_PREFIX + lineId + ":" + paymentSeq;
  }

  /**
   * Undo of one paid line (R9.5): a compensating entry Dr cash or bank / Cr 2400 for the line's
   * net only, not a reversal of the whole batch.
   *
   * @return the entry (existing on retry), or null when the line's net is zero
   */
  public JournalEntryView postPaymentUndo(String shopId, String userId, PayrollLine line,
      int paymentSeq, HrPaymentMethod method, LocalDate date) {
    BigDecimal net = nz(line.getNet());
    if (net.signum() <= 0) {
      return null;
    }
    String memo = memo(line.getEmployeeSnapshot() != null
        ? line.getEmployeeSnapshot().getCode() : null);
    List<PostJournalLine> out = List.of(
        debit(cashOrBank(method), net, memo),
        credit(SystemAccountCode.SALARY_PAYABLE, net, memo));
    PostJournalRequest req = request(JournalSource.SALARY_PAYMENT,
        undoSourceId(line.getId(), paymentSeq), date, "Salary payment undone · " + memo, out);
    return post(shopId, userId, req);
  }

  // ---- reversal --------------------------------------------------------------------------------

  /**
   * Reverses the entry posted for {@code (sourceType, sourceId)}, unless it is already reversed
   * ({@code AccountingFacade.reverse} throws on a reversed entry). The original is found by its
   * source because the facade has no lookup by id. Safe to repeat after a crash: the reversal is
   * itself posted under {@code REV:<entryId>}, so a second call finishes the first one's work.
   *
   * @return the reversal entry id, or null when nothing was posted for the source
   */
  public String reverseBySource(String shopId, String userId, JournalSource sourceType,
      String sourceId, String reason) {
    Optional<JournalEntryView> original = accountingFacade.findBySource(shopId, sourceType, sourceId);
    if (original.isEmpty()) {
      return null;
    }
    JournalEntryView entry = original.get();
    if (entry.status() == JournalStatus.REVERSED) {
      return entry.reversedByEntryId();
    }
    JournalEntryView reversal = accountingFacade.reverse(shopId, userId, entry.id(), reason);
    log.info("[hr-accounting] reversed {} {} as {}", sourceType, sourceId, reversal.id());
    return reversal.id();
  }

  /** The finalise journal of the run's current revision, if posted. */
  public Optional<JournalEntryView> findRunJournal(String shopId, PayrollRun run) {
    return accountingFacade.findBySource(shopId, JournalSource.PAYROLL_RUN, runSourceId(run));
  }

  /**
   * The SALARY_PAYMENT entry of a batch ({@code paymentBatchId}) or of an undo ({@link
   * #undoSourceId}), if posted: a failed post may still have saved it (no transactions).
   */
  public Optional<JournalEntryView> findPaymentJournal(String shopId, String sourceId) {
    return accountingFacade.findBySource(shopId, JournalSource.SALARY_PAYMENT, sourceId);
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /**
   * Posts after checking that every HR code the entry uses is the seeded system account (one
   * read): a shop's own account on 1250/2400/5700 would silently take salary postings.
   */
  private JournalEntryView post(String shopId, String userId, PostJournalRequest req) {
    Set<String> hrCodes = new TreeSet<>();
    for (PostJournalLine l : req.getLines()) {
      if (l.getAccountCode() != null && HR_SYSTEM_CODES.contains(l.getAccountCode())) {
        hrCodes.add(l.getAccountCode());
      }
    }
    assertHrAccounts(shopId, hrCodes);
    return accountingFacade.post(shopId, userId, req);
  }

  /**
   * 409 {@code HR_ACCOUNT_CONFLICT} when the shop holds one of {@code codes} (HR system codes)
   * as an account of its own; one read, none for an empty set. Also used before an advance is
   * saved, so the user hears about it instead of the advance staying "entry pending".
   */
  public void assertHrAccounts(String shopId, Collection<String> codes) {
    if (codes.isEmpty()) {
      return;
    }
    Set<String> own = accountingFacade.ownAccountCodes(shopId, codes);
    if (!own.isEmpty()) {
      String code = own.iterator().next();
      // shop id and codes only
      log.warn("[hr-accounting] shop {} has its own account on {}; nothing posted", shopId, own);
      metrics.increment(HrMetricsConstants.ACCOUNT_CONFLICTS_TOTAL, 1,
          "module", HrMetricsConstants.MODULE, "code", code);
      throw new BaseException(ErrorCode.HR_ACCOUNT_CONFLICT, "Account " + code
          + " in your chart of accounts is your own account. Rename or move it so payroll"
          + " can use " + code + ".");
    }
  }

  /** The post was refused by {@link #assertHrAccounts}: nothing reached accounting. */
  public static boolean isAccountConflict(Throwable e) {
    return e instanceof BaseException b && b.getErrorCode() == ErrorCode.HR_ACCOUNT_CONFLICT;
  }

  /** UPI goes to Bank (R11.4); null counts as cash. */
  static String cashOrBank(HrPaymentMethod method) {
    return method == HrPaymentMethod.BANK || method == HrPaymentMethod.UPI
        ? SystemAccountCode.BANK : SystemAccountCode.CASH;
  }

  private static PostJournalRequest request(JournalSource type, String sourceId, LocalDate date,
      String narration, List<PostJournalLine> lines) {
    PostJournalRequest req = new PostJournalRequest();
    req.setSourceType(type);
    req.setSourceId(sourceId);
    req.setSourceKey(type.name() + ":" + sourceId);
    req.setTxnDate(date);
    req.setNarration(narration);
    req.setLines(new ArrayList<>(lines));
    return req;
  }

  private static PostJournalLine debit(String code, BigDecimal amount, String memo) {
    PostJournalLine l = line(code, memo);
    l.setDebit(amount);
    return l;
  }

  private static PostJournalLine credit(String code, BigDecimal amount, String memo) {
    PostJournalLine l = line(code, memo);
    l.setCredit(amount);
    return l;
  }

  private static PostJournalLine line(String code, String memo) {
    PostJournalLine l = new PostJournalLine();
    l.setAccountCode(code);
    l.setPartyType(PartyType.SHOP);
    l.setMemo(memo);
    return l;
  }

  private static String memo(String employeeCode) {
    return employeeCode != null ? employeeCode : "employee";
  }

  private static int revisionOf(PayrollRun run) {
    return run.getRevision() != null ? run.getRevision() : 0;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v != null ? v : BigDecimal.ZERO;
  }
}
