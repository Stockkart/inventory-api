package com.inventory.accounting.rest.dto.mapping;

import com.inventory.accounting.domain.model.GlAccount;
import com.inventory.accounting.domain.model.JournalEntry;
import com.inventory.accounting.domain.model.JournalLine;
import com.inventory.accounting.domain.model.SubledgerEntry;
import com.inventory.accounting.rest.dto.response.GlAccountResponse;
import com.inventory.accounting.rest.dto.response.JournalEntryResponse;
import com.inventory.accounting.rest.dto.response.JournalLineResponse;
import com.inventory.accounting.rest.dto.response.SubledgerEntriesPageResponse;
import com.inventory.accounting.service.TrialBalanceQueryService.DebitCreditTotals;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

public final class AccountingMapper {

  private AccountingMapper() {}

  public static GlAccountResponse toResponse(GlAccount a, DebitCreditTotals activity) {
    BigDecimal debit = activity != null ? activity.debit() : BigDecimal.ZERO;
    BigDecimal credit = activity != null ? activity.credit() : BigDecimal.ZERO;
    return new GlAccountResponse(
        a.getId(),
        a.getCode(),
        a.getName(),
        a.getAccountType(),
        a.isSystemAccount(),
        a.isActive(),
        debit,
        credit);
  }

  public static JournalEntryResponse toResponse(JournalEntry e) {
    List<JournalLineResponse> lines =
        e.getLines() == null
            ? List.of()
            : e.getLines().stream().map(AccountingMapper::toResponse).collect(Collectors.toList());
    return new JournalEntryResponse(
        e.getId(),
        e.getShopId(),
        e.getJournalDate(),
        e.getPostedAt(),
        e.getDescription(),
        e.getSource(),
        e.getSourceKey(),
        e.getTotalDebitSum(),
        e.getTotalCreditSum(),
        e.getPostedByUserId(),
        lines);
  }

  public static JournalLineResponse toResponse(JournalLine l) {
    return new JournalLineResponse(
        l.getLineNo(),
        l.getAccountId(),
        l.getAccountCode(),
        l.getDebit(),
        l.getCredit(),
        l.getMemo(),
        l.getPartyType(),
        l.getPartyId());
  }

  public static SubledgerEntriesPageResponse.EntryRow toRow(SubledgerEntry e) {
    return new SubledgerEntriesPageResponse.EntryRow(
        e.getId(),
        e.getJournalEntryId(),
        e.getJournalLineNo(),
        e.getPartyType(),
        e.getPartyId(),
        e.getKind() != null ? e.getKind().name() : null,
        e.getAmount(),
        e.getMemo(),
        e.getJournalDate(),
        e.getPostedAt(),
        e.getPostedByUserId(),
        e.getJournalSourceKey());
  }
}
