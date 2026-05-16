package com.inventory.accounting.service;

import com.inventory.accounting.domain.model.Account;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.LedgerEntry;
import com.inventory.accounting.rest.dto.response.AccountResponse;
import com.inventory.accounting.rest.dto.response.JournalEntryResponse;
import com.inventory.accounting.rest.dto.response.JournalLineResponse;
import com.inventory.accounting.rest.dto.response.LedgerEntryResponse;
import com.inventory.accounting.rest.dto.response.PartyStatementEntryResponse;
import com.inventory.accounting.rest.dto.response.PartyStatementResponse;
import com.inventory.accounting.rest.dto.response.PartySummariesResponse;
import com.inventory.accounting.rest.dto.response.PartySummaryResponse;
import com.inventory.accounting.rest.dto.response.BalanceSheetResponse;
import com.inventory.accounting.rest.dto.response.FinancialReportLineDto;
import com.inventory.accounting.rest.dto.response.ProfitAndLossResponse;
import com.inventory.accounting.rest.dto.response.TrialBalanceResponse;
import java.util.List;

/** Pure mapping between domain documents and REST DTOs. */
public final class AccountingMapper {

  private AccountingMapper() {}

  public static AccountResponse toResponse(Account a) {
    if (a == null) return null;
    return new AccountResponse(
        a.getId(),
        a.getCode(),
        a.getName(),
        a.getType(),
        a.getNormalBalance(),
        a.getParentCode(),
        a.isSystem(),
        a.isActive(),
        a.getCreatedAt(),
        a.getUpdatedAt());
  }

  public static JournalLineResponse toResponse(JournalLine l) {
    if (l == null) return null;
    return new JournalLineResponse(
        l.getLineIndex(),
        l.getAccountId(),
        l.getAccountCode(),
        l.getAccountName(),
        l.getDebit(),
        l.getCredit(),
        l.getPartyType(),
        l.getPartyRefId(),
        l.getPartyDisplayName(),
        l.getMemo());
  }

  public static JournalEntryResponse toResponse(JournalEntry e) {
    if (e == null) return null;
    List<JournalLineResponse> lines =
        e.getLines() != null
            ? e.getLines().stream().map(AccountingMapper::toResponse).toList()
            : List.of();
    return new JournalEntryResponse(
        e.getId(),
        e.getEntryNo(),
        e.getTxnDate(),
        e.getPostedAt(),
        e.getSourceType(),
        e.getSourceId(),
        e.getStatus(),
        e.getReversesEntryId(),
        e.getReversedByEntryId(),
        e.getNarration(),
        lines,
        e.getTotalDebit(),
        e.getTotalCredit(),
        e.getCreatedByUserId());
  }

  public static LedgerEntryResponse toResponse(LedgerEntry l) {
    if (l == null) return null;
    return new LedgerEntryResponse(
        l.getId(),
        l.getJournalEntryId(),
        l.getJournalEntryNo(),
        l.getSourceType(),
        l.getSourceId(),
        l.getTxnDate(),
        l.getPostedAt(),
        l.getDebit(),
        l.getCredit(),
        l.getBalanceAfter(),
        l.getPartyType(),
        l.getPartyRefId(),
        l.getPartyDisplayName(),
        l.getNarration());
  }

  public static TrialBalanceResponse.Row toResponse(TrialBalanceService.TrialBalanceRow row) {
    if (row == null) return null;
    return new TrialBalanceResponse.Row(
        row.accountId(),
        row.accountCode(),
        row.accountName(),
        row.accountType(),
        row.normalBalance(),
        row.debitTurnover(),
        row.creditTurnover(),
        row.debitBalance(),
        row.creditBalance());
  }

  public static TrialBalanceResponse toResponse(TrialBalanceService.TrialBalance tb) {
    return new TrialBalanceResponse(
        tb.asOf(),
        tb.rows().stream().map(AccountingMapper::toResponse).toList(),
        tb.totalDebit(),
        tb.totalCredit());
  }

  public static PartySummaryResponse toResponse(PartyLedgerService.PartyRow r) {
    if (r == null) return null;
    return new PartySummaryResponse(
        r.partyType(),
        r.partyRefId(),
        r.partyDisplayName(),
        r.debitTurnover(),
        r.creditTurnover(),
        r.balance(),
        r.lastTxnDate(),
        r.txnCount());
  }

  public static PartySummariesResponse toResponse(PartyLedgerService.PartySummary s) {
    if (s == null) return null;
    return new PartySummariesResponse(
        s.partyType(),
        s.from(),
        s.to(),
        s.asOf(),
        s.parties().stream().map(AccountingMapper::toResponse).toList(),
        s.totalDebit(),
        s.totalCredit(),
        s.totalBalance());
  }

  public static PartyStatementEntryResponse toResponse(PartyLedgerService.EnrichedRow row) {
    if (row == null || row.entry() == null) return null;
    var e = row.entry();
    return new PartyStatementEntryResponse(
        e.getId(),
        e.getJournalEntryId(),
        e.getJournalEntryNo(),
        e.getSourceType(),
        e.getSourceId(),
        e.getTxnDate(),
        e.getPostedAt(),
        e.getAccountId(),
        e.getAccountCode(),
        e.getAccountName(),
        e.getDebit(),
        e.getCredit(),
        row.balanceAfter(),
        e.getNarration());
  }

  public static FinancialReportLineDto toResponse(FinancialReportsService.ReportLine line) {
    if (line == null) return null;
    return new FinancialReportLineDto(
        line.accountId(),
        line.accountCode(),
        line.accountName(),
        line.accountType(),
        line.amount());
  }

  public static ProfitAndLossResponse toResponse(FinancialReportsService.ProfitAndLoss pl) {
    if (pl == null) return null;
    return new ProfitAndLossResponse(
        pl.from(),
        pl.to(),
        pl.revenueLines().stream().map(AccountingMapper::toResponse).toList(),
        pl.expenseLines().stream().map(AccountingMapper::toResponse).toList(),
        pl.totalRevenue(),
        pl.totalExpense(),
        pl.netProfit());
  }

  public static BalanceSheetResponse toResponse(FinancialReportsService.BalanceSheet bs) {
    if (bs == null) return null;
    return new BalanceSheetResponse(
        bs.asOf(),
        bs.assets().stream().map(AccountingMapper::toResponse).toList(),
        bs.liabilities().stream().map(AccountingMapper::toResponse).toList(),
        bs.equity().stream().map(AccountingMapper::toResponse).toList(),
        bs.totalAssets(),
        bs.totalLiabilities(),
        bs.totalEquity(),
        bs.totalLiabilitiesAndEquity(),
        bs.imbalance());
  }

  public static PartyStatementResponse toResponse(PartyLedgerService.Statement st) {
    if (st == null) return null;
    return new PartyStatementResponse(
        st.partyType(),
        st.partyRefId(),
        st.partyDisplayName(),
        st.openingBalance(),
        st.closingBalance(),
        st.entries().stream().map(AccountingMapper::toResponse).toList(),
        st.page(),
        st.size(),
        st.totalItems(),
        st.totalPages());
  }
}
