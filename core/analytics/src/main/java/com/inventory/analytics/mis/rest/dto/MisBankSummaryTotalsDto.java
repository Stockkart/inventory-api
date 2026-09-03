package com.inventory.analytics.mis.rest.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Grand total across every company, matching the TOTAL line of the legacy report. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MisBankSummaryTotalsDto {
  private long companyCount;
  private BigDecimal opening;
  private BigDecimal purchase;
  private BigDecimal sale;
  private BigDecimal adjustment;
  private BigDecimal closing;
}
