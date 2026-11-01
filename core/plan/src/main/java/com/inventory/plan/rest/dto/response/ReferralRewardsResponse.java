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
public class ReferralRewardsResponse {
  /** Earned but still in the hold period (PENDING, APPROVED or being credited). */
  private BigDecimal pendingAmount;
  private BigDecimal creditedAmount;
  /** Newest first, at most 100. */
  private List<ReferralRewardResponse> rewards;
}
