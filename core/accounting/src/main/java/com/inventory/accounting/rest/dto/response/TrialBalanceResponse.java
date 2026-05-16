package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.AccountType;
import com.inventory.accounting.domain.model.NormalBalance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrialBalanceResponse {

  private LocalDate asOf;
  private List<Row> rows;
  private BigDecimal totalDebit;
  private BigDecimal totalCredit;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Row {
    private String accountId;
    private String accountCode;
    private String accountName;
    private AccountType accountType;
    private NormalBalance normalBalance;
    private BigDecimal debitTurnover;
    private BigDecimal creditTurnover;
    private BigDecimal debitBalance;
    private BigDecimal creditBalance;
  }
}
