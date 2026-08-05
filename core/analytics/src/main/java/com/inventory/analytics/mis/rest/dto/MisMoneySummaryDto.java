package com.inventory.analytics.mis.rest.dto;

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
public class MisMoneySummaryDto {
  private BigDecimal openingBalanceTotal;
  private BigDecimal periodCashTotal;
  private BigDecimal periodOnlineTotal;
  private BigDecimal periodCreditTotal;
  /** Vendor: purchases total; Customer: sales credit / bill total depending on report. */
  private BigDecimal periodPurchaseOrSaleTotal;
  private BigDecimal currentBalanceTotal;
  @Builder.Default private List<MisMoneyPartySummaryDto> partySummaries = new ArrayList<>();
}
