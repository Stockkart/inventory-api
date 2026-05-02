package com.inventory.accounting.rest.dto.response;

import com.inventory.accounting.domain.model.AccountType;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GlAccountResponse {
  private String id;
  private String code;
  private String name;
  private AccountType accountType;
  private boolean systemAccount;
  private boolean active;
  /** Sum of debits on this GL account across all posted journals (trial-balance scope). */
  private BigDecimal totalDebit;
  /** Sum of credits on this GL account across all posted journals (trial-balance scope). */
  private BigDecimal totalCredit;
}
