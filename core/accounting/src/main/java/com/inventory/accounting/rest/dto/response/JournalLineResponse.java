package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JournalLineResponse {
  private int lineIndex;
  private String accountId;
  private String accountCode;
  private String accountName;
  private BigDecimal debit;
  private BigDecimal credit;
  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;
  private String memo;
}
