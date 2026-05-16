package com.inventory.accounting.service;

import static com.inventory.accounting.service.MoneyUtil.scale;
import static com.inventory.accounting.service.MoneyUtil.zero;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import com.inventory.common.exception.ValidationException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P&amp;L and Balance Sheet built from ledger aggregates. */
@Service
@RequiredArgsConstructor
public class FinancialReportsService {

  private final AccountService accountService;
  private final TrialBalanceService trialBalanceService;

  @Transactional(readOnly = true)
  public ProfitAndLoss profitAndLoss(String shopId, LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new ValidationException("from and to dates are required for profit and loss");
    }
    if (from.isAfter(to)) {
      throw new ValidationException("from date cannot be after to date");
    }

    List<Account> accounts = accountService.list(shopId);
    Map<String, TrialBalanceService.AccountTotals> turnover =
        trialBalanceService.turnoverBetween(shopId, from, to);

    List<ReportLine> revenueLines = new ArrayList<>();
    List<ReportLine> expenseLines = new ArrayList<>();
    BigDecimal totalRevenue = zero();
    BigDecimal totalExpense = zero();

    for (Account a : accounts) {
      if (!a.isActive()) continue;
      TrialBalanceService.AccountTotals t =
          turnover.getOrDefault(a.getId(), TrialBalanceService.AccountTotals.EMPTY);
      BigDecimal periodAmount = periodAmount(a, t);
      if (periodAmount.signum() == 0) continue;

      ReportLine line =
          new ReportLine(a.getId(), a.getCode(), a.getName(), a.getType(), scale(periodAmount));
      if (a.getType() == AccountType.REVENUE) {
        revenueLines.add(line);
        totalRevenue = scale(totalRevenue.add(periodAmount));
      } else if (a.getType() == AccountType.EXPENSE) {
        expenseLines.add(line);
        totalExpense = scale(totalExpense.add(periodAmount));
      }
    }

    revenueLines.sort(Comparator.comparing(ReportLine::accountCode));
    expenseLines.sort(Comparator.comparing(ReportLine::accountCode));

    BigDecimal netProfit = scale(totalRevenue.subtract(totalExpense));
    return new ProfitAndLoss(from, to, revenueLines, expenseLines, totalRevenue, totalExpense, netProfit);
  }

  @Transactional(readOnly = true)
  public BalanceSheet balanceSheet(String shopId, LocalDate asOf) {
    TrialBalanceService.TrialBalance tb = trialBalanceService.asOf(shopId, asOf);

    List<ReportLine> assets = new ArrayList<>();
    List<ReportLine> liabilities = new ArrayList<>();
    List<ReportLine> equity = new ArrayList<>();
    BigDecimal totalAssets = zero();
    BigDecimal totalLiabilities = zero();
    BigDecimal totalEquity = zero();

    for (TrialBalanceService.TrialBalanceRow row : tb.rows()) {
      BigDecimal bal = signedBalance(row);
      if (bal.signum() == 0) continue;
      ReportLine line =
          new ReportLine(
              row.accountId(),
              row.accountCode(),
              row.accountName(),
              row.accountType(),
              scale(bal.abs()));
      switch (row.accountType()) {
        case ASSET -> {
          assets.add(line);
          totalAssets = scale(totalAssets.add(bal));
        }
        case LIABILITY -> {
          liabilities.add(line);
          totalLiabilities = scale(totalLiabilities.add(bal));
        }
        case EQUITY -> {
          equity.add(line);
          totalEquity = scale(totalEquity.add(bal));
        }
        default -> {}
      }
    }

    assets.sort(Comparator.comparing(ReportLine::accountCode));
    liabilities.sort(Comparator.comparing(ReportLine::accountCode));
    equity.sort(Comparator.comparing(ReportLine::accountCode));

    BigDecimal liabilitiesAndEquity = scale(totalLiabilities.add(totalEquity));
    return new BalanceSheet(
        tb.asOf(),
        assets,
        liabilities,
        equity,
        totalAssets,
        totalLiabilities,
        totalEquity,
        liabilitiesAndEquity,
        scale(totalAssets.subtract(liabilitiesAndEquity)));
  }

  private static BigDecimal periodAmount(Account a, TrialBalanceService.AccountTotals t) {
    if (a.getType() == AccountType.REVENUE) {
      return a.getNormalBalance() == NormalBalance.CREDIT
          ? scale(t.creditTotal().subtract(t.debitTotal()))
          : scale(t.debitTotal().subtract(t.creditTotal()));
    }
    if (a.getType() == AccountType.EXPENSE) {
      return a.getNormalBalance() == NormalBalance.DEBIT
          ? scale(t.debitTotal().subtract(t.creditTotal()))
          : scale(t.creditTotal().subtract(t.debitTotal()));
    }
    return zero();
  }

  private static BigDecimal signedBalance(TrialBalanceService.TrialBalanceRow row) {
    return row.closingSigned() != null ? row.closingSigned() : zero();
  }

  public record ReportLine(
      String accountId, String accountCode, String accountName, AccountType accountType, BigDecimal amount) {}

  public record ProfitAndLoss(
      LocalDate from,
      LocalDate to,
      List<ReportLine> revenueLines,
      List<ReportLine> expenseLines,
      BigDecimal totalRevenue,
      BigDecimal totalExpense,
      BigDecimal netProfit) {}

  public record BalanceSheet(
      LocalDate asOf,
      List<ReportLine> assets,
      List<ReportLine> liabilities,
      List<ReportLine> equity,
      BigDecimal totalAssets,
      BigDecimal totalLiabilities,
      BigDecimal totalEquity,
      BigDecimal totalLiabilitiesAndEquity,
      BigDecimal imbalance) {}
}
