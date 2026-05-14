package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.PartyType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Aggregated subsidiary listing for one {@link PartyType} (all vendors or all customers). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PartySummariesResponse {
  private PartyType partyType;
  private LocalDate from;
  private LocalDate to;
  private LocalDate asOf;
  private List<PartySummaryResponse> parties;
  private BigDecimal totalDebit;
  private BigDecimal totalCredit;
  private BigDecimal totalBalance;
}
