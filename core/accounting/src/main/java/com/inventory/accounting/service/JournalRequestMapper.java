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
    return baseLine(in.getAccountCode(), in.getAccountId(), in.getDebit(), in.getCredit())
        .withParty(in.getPartyType(), trim(in.getPartyRefId()), trim(in.getPartyDisplayName()))
        .toBuilder()
        .memo(trim(in.getMemo()))
        .build();
  }

  private static PostJournalLine fromOpeningLine(OpeningBalanceRequest.Line in) {
    return baseLine(in.getAccountCode(), in.getAccountId(), in.getDebit(), in.getCredit())
        .withParty(in.getPartyType(), trim(in.getPartyRefId()), trim(in.getPartyDisplayName()))
        .toBuilder()
        .memo(trim(in.getMemo()))
        .build();
  }

  private static PostJournalLine baseLine(
      String accountCode, String accountId, java.math.BigDecimal debit, java.math.BigDecimal credit) {
    return PostJournalLine.builder()
        .accountCode(trim(accountCode))
        .accountId(trim(accountId))
        .debit(debit)
        .credit(credit)
        .build();
  }

  private static String trim(String raw) {
    if (raw == null) return null;
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }
}
