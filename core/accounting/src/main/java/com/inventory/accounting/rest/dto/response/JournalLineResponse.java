package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
public class JournalLineResponse {
  private int lineNo;
  private String accountId;
  private String accountCode;
  private BigDecimal debit;
  private BigDecimal credit;
  private String memo;
  private PartyType partyType;
  private String partyId;
}
