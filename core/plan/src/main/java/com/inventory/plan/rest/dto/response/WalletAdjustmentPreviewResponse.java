package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What an adjustment would do to a wallet. Nothing is written. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletAdjustmentPreviewResponse {

  /** The amount as it would be applied (2 decimals; negative takes from the wallet). */
  private BigDecimal amount;
  /** Available balance now. */
  private BigDecimal currentBalance;
  /** Available balance after the adjustment; equals currentBalance when it is not allowed. */
  private BigDecimal resultingBalance;
  /** False when the wallet does not have enough available to take this amount. */
  private boolean allowed;
  /** Largest single adjustment either way. */
  private BigDecimal maxAdjustment;
}
