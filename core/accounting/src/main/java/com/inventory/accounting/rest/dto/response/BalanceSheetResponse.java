package com.inventory.accounting.rest.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BalanceSheetResponse {
  private LocalDate asOf;
  private List<FinancialReportLineDto> assets;
  private List<FinancialReportLineDto> liabilities;
  private List<FinancialReportLineDto> equity;
  private BigDecimal totalAssets;
  private BigDecimal totalLiabilities;
  private BigDecimal totalEquity;
  private BigDecimal totalLiabilitiesAndEquity;
  private BigDecimal imbalance;
}
