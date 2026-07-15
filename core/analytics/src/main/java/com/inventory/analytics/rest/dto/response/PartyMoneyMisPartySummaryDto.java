package com.inventory.analytics.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartyMoneyMisPartySummaryDto {
  private String partyId;
  private String partyName;
  private BigDecimal openingBalance;
  private BigDecimal closingBalanceInPeriod;
  private BigDecimal currentBalance;
}
