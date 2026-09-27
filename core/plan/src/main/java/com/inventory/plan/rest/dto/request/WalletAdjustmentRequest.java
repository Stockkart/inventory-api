package com.inventory.plan.rest.dto.request;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WalletAdjustmentRequest {

  /** Positive credits the wallet; negative takes from the available balance. Never zero. */
  private BigDecimal amount;
  /** Required; kept on the ledger entry and in the audit log. */
  private String reason;
  /** Optional client id; resending the same id does not apply the adjustment twice. */
  private String adjustmentId;
}
