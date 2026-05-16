package com.inventory.accounting.service;

import com.inventory.accounting.api.PostJournalLine;
import com.inventory.accounting.rest.dto.request.CreateJournalEntryRequest;
import com.inventory.accounting.rest.dto.request.OpeningBalanceRequest;
import com.inventory.common.exception.ValidationException;
import java.util.List;

/** Maps REST journal line DTOs to {@link PostJournalLine}. */
public final class JournalRequestMapper {

  private JournalRequestMapper() {}

  public static List<PostJournalLine> fromManual(CreateJournalEntryRequest body) {
    if (body == null || body.getLines() == null) {
      throw new ValidationException("Journal lines are required");
    }
    return body.getLines().stream().map(JournalRequestMapper::fromManualLine).toList();
  }

  public static List<PostJournalLine> fromOpening(OpeningBalanceRequest body) {
    if (body == null || body.getLines() == null) {
      throw new ValidationException("Opening balance lines are required");
    }
    return body.getLines().stream().map(JournalRequestMapper::fromOpeningLine).toList();
  }

  private static PostJournalLine fromManualLine(CreateJournalEntryRequest.Line in) {
    PostJournalLine l = baseLine(in.getAccountCode(), in.getAccountId(), in.getDebit(), in.getCredit());
    l.setPartyType(in.getPartyType());
    l.setPartyRefId(trim(in.getPartyRefId()));
    l.setPartyDisplayName(trim(in.getPartyDisplayName()));
    l.setMemo(trim(in.getMemo()));
    return l;
  }

  private static PostJournalLine fromOpeningLine(OpeningBalanceRequest.Line in) {
    PostJournalLine l = baseLine(in.getAccountCode(), in.getAccountId(), in.getDebit(), in.getCredit());
    l.setPartyType(in.getPartyType());
    l.setPartyRefId(trim(in.getPartyRefId()));
    l.setPartyDisplayName(trim(in.getPartyDisplayName()));
    l.setMemo(trim(in.getMemo()));
    return l;
  }

  private static PostJournalLine baseLine(
      String accountCode, String accountId, java.math.BigDecimal debit, java.math.BigDecimal credit) {
    PostJournalLine l = new PostJournalLine();
    l.setAccountCode(trim(accountCode));
    l.setAccountId(trim(accountId));
    l.setDebit(debit);
    l.setCredit(credit);
    return l;
  }

  private static String trim(String raw) {
    if (raw == null) return null;
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }
}
