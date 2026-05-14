package com.inventory.accounting.service;

import static com.inventory.accounting.service.MoneyUtil.nz;
import static com.inventory.accounting.service.MoneyUtil.scale;
import static com.inventory.accounting.service.MoneyUtil.zero;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.accounting.domain.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Produces the Trial Balance and the underlying per-account turnover/balance numbers used by the
 * P&amp;L and Balance Sheet reports. All numbers are computed by aggregating {@link LedgerEntry}
 * documents on the server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrialBalanceService {

  private final AccountService accountService;
  private final LedgerEntryRepository ledgerEntryRepository;
  private final MongoTemplate mongoTemplate;

  @Transactional(readOnly = true)
  public TrialBalance asOf(String shopId, LocalDate asOf) {
    // When the caller doesn't pin an "as of" date we want everything posted so far. Passing
    // {@code null} here drops the upper-bound predicate in {@link #aggregateTotals}, which avoids
    // a server-vs-user timezone trap: a JE posted today in IST is stored at UTC midnight of the
    // user's local date, which may be tomorrow in UTC; a naive {@code LocalDate.now()} cutoff
    // on a UTC server would then drop that JE one day in the future.

    log.info("asOf function: {}", asOf);
    List<Account> accounts = accountService.list(shopId);

    Map<String, AccountTotals> totals = aggregateTotals(shopId, null, asOf);

    List<TrialBalanceRow> rows = new ArrayList<>(accounts.size());
    BigDecimal totalDebit = zero();
    BigDecimal totalCredit = zero();

    for (Account a : accounts) {
      AccountTotals t = totals.getOrDefault(a.getId(), AccountTotals.EMPTY);
      BigDecimal closingSigned =
          a.getNormalBalance() == NormalBalance.DEBIT
              ? scale(t.debitTotal.subtract(t.creditTotal))
              : scale(t.creditTotal.subtract(t.debitTotal));
      BigDecimal debitBalance = closingSigned.signum() > 0
          ? (a.getNormalBalance() == NormalBalance.DEBIT ? closingSigned : zero())
          : (a.getNormalBalance() == NormalBalance.DEBIT ? zero() : closingSigned.negate());
      BigDecimal creditBalance = closingSigned.signum() > 0
          ? (a.getNormalBalance() == NormalBalance.CREDIT ? closingSigned : zero())
          : (a.getNormalBalance() == NormalBalance.CREDIT ? zero() : closingSigned.negate());

      rows.add(
          new TrialBalanceRow(
              a.getId(),
              a.getCode(),
              a.getName(),
              a.getType(),
              a.getNormalBalance(),
              scale(t.debitTotal),
              scale(t.creditTotal),
              scale(debitBalance),
              scale(creditBalance),
              scale(closingSigned)));
      totalDebit = scale(totalDebit.add(debitBalance));
      totalCredit = scale(totalCredit.add(creditBalance));
    }

    return new TrialBalance(
        asOf != null ? asOf : LocalDate.now(), rows, totalDebit, totalCredit);
  }

  /**
   * Period-bound aggregation: returns per-account {@code debit/credit} turnover between {@code
   * from} (inclusive) and {@code to} (inclusive). Used by P&amp;L computations.
   */
  @Transactional(readOnly = true)
  public Map<String, AccountTotals> turnoverBetween(
      String shopId, LocalDate from, LocalDate to) {
    return aggregateTotals(shopId, from, to);
  }

  private Map<String, AccountTotals> aggregateTotals(
      String shopId, LocalDate from, LocalDate to) {
    Criteria c = Criteria.where("shopId").is(shopId);
    // {@code txnDate} is stored as a BSON Date at midnight UTC of the LocalDate set by the
    // posting flow. Use {@code lt next-day-midnight} for the upper bound so a JE whose user-local
    // date equals {@code to} is included even when the server runs in a different timezone — a
    // plain {@code lte(to)} would otherwise drop entries stored at the very start of {@code to}.
    if (from != null && to != null) {
      c = c.and("txnDate").gte(from).lt(to.plusDays(1));
    } else if (from != null) {
      c = c.and("txnDate").gte(from);
    } else if (to != null) {
      c = c.and("txnDate").lt(to.plusDays(1));
    }
    Aggregation agg =
        Aggregation.newAggregation(
            Aggregation.match(c),
            Aggregation.group("accountId")
                .sum("debit")
                .as("debitTotal")
                .sum("credit")
                .as("creditTotal"));
    AggregationResults<Document> results =
        mongoTemplate.aggregate(agg, "ledger_entries", Document.class);
    Map<String, AccountTotals> map = new HashMap<>();
    for (Document r : results.getMappedResults()) {
      String accountId = r.getString("_id");
      if (accountId == null) continue;
      map.put(
          accountId,
          new AccountTotals(toBigDecimal(r.get("debitTotal")), toBigDecimal(r.get("creditTotal"))));
    }
    return map;
  }

  private static BigDecimal toBigDecimal(Object v) {
    if (v == null) return zero();
    if (v instanceof BigDecimal bd) return nz(bd);
    if (v instanceof Number n) return nz(BigDecimal.valueOf(n.doubleValue()));
    return nz(new BigDecimal(v.toString()));
  }

  /** Per-account turnover totals. */
  public record AccountTotals(BigDecimal debitTotal, BigDecimal creditTotal) {
    public static final AccountTotals EMPTY = new AccountTotals(zero(), zero());
  }

  /** Single trial balance row. */
  public record TrialBalanceRow(
      String accountId,
      String accountCode,
      String accountName,
      AccountType accountType,
      NormalBalance normalBalance,
      BigDecimal debitTurnover,
      BigDecimal creditTurnover,
      BigDecimal debitBalance,
      BigDecimal creditBalance,
      BigDecimal closingSigned) {}

  /** Full trial balance view. */
  public record TrialBalance(
      LocalDate asOf, List<TrialBalanceRow> rows, BigDecimal totalDebit, BigDecimal totalCredit) {}
}
