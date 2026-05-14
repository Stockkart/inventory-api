package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One row in the subsidiary listing for a {@link PartyType}. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartySummaryResponse {
  private PartyType partyType;
  private String partyRefId;
  private String partyDisplayName;
  private BigDecimal debitTurnover;
  private BigDecimal creditTurnover;
  private BigDecimal balance;
  private LocalDate lastTxnDate;
  private int txnCount;
}
