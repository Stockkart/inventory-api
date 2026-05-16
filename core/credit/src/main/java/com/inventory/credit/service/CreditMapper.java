package com.inventory.credit.service;

import com.inventory.credit.domain.model.CreditAccount;
import com.inventory.credit.domain.model.CreditEntry;
import com.inventory.credit.rest.dto.response.CreditAccountResponse;
import com.inventory.credit.rest.dto.response.CreditEntryResponse;

public final class CreditMapper {

  private CreditMapper() {}

  public static CreditAccountResponse toResponse(CreditAccount a) {
    return new CreditAccountResponse(
        a.getId(),
        a.getPartyType(),
        a.getPartyRefId(),
        a.getPartyDisplayName(),
        a.getPartyPhone(),
        a.getCurrentBalance(),
        a.getStatus(),
        a.getUpdatedAt(),
        a.getLastEntryAt());
  }

  public static CreditEntryResponse toResponse(CreditEntry e) {
    return new CreditEntryResponse(
        e.getId(),
        e.getAccountId(),
        e.getEntryType(),
        e.getDirection(),
        e.getAmount(),
        e.getBalanceAfter(),
        e.getNote(),
        e.getReferenceType(),
        e.getReferenceId(),
        e.getSourceKey(),
        e.getPaymentMethod(),
        e.getBankRef(),
        e.getTxnDate(),
        e.getCreatedByUserId(),
        e.getCreatedAt());
  }
}
