package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.AccountType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FinancialReportLineDto {
  private String accountId;
  private String accountCode;
  private String accountName;
  private AccountType accountType;
  private BigDecimal amount;
}
