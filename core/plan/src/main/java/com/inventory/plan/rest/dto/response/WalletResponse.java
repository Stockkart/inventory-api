package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletResponse {
  /** Spendable at checkout now. */
  private BigDecimal availableBalance;
  /** Held by checkouts that have not been paid yet. */
  private BigDecimal reservedBalance;
  /** Owed back after a refunded referral; settled from future credit first. */
  private BigDecimal outstandingClawback;
  /** Most recent movements first. */
  private List<WalletEntryResponse> entries;
}
