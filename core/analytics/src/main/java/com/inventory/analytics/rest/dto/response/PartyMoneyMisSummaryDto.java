package com.inventory.analytics.rest.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartyMoneyMisSummaryDto {
  private BigDecimal openingBalanceTotal;
  private BigDecimal periodCashTotal;
  private BigDecimal periodOnlineTotal;
  private BigDecimal periodCreditTotal;
  private BigDecimal periodPurchaseTotal;
  private BigDecimal currentPayableTotal;
  @Builder.Default private List<PartyMoneyMisPartySummaryDto> partySummaries = new ArrayList<>();
}
