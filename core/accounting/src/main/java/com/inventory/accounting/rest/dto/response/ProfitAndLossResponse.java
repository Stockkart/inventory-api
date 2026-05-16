package com.inventory.accounting.rest.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ProfitAndLossResponse {
  private LocalDate from;
  private LocalDate to;
  private List<FinancialReportLineDto> revenueLines;
  private List<FinancialReportLineDto> expenseLines;
  private BigDecimal totalRevenue;
  private BigDecimal totalExpense;
  private BigDecimal netProfit;
}
