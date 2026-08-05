package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisMoneyPartySummaryDto {
  private String partyId;
  private String partyName;
  private BigDecimal openingBalance;
  private BigDecimal closingBalanceInPeriod;
  private BigDecimal currentBalance;
}
