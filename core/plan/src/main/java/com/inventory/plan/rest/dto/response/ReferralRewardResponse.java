package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.ReferralRewardStatus;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralRewardResponse {
  private String id;
  private String refereeShopName;
  private String planCode;
  private BigDecimal basePlanAmount;
  private BigDecimal rewardPercent;
  private BigDecimal rewardAmount;
  private ReferralRewardStatus status;
  /** Reaches the wallet after this, unless the purchase is refunded first. */
  private Instant holdUntil;
  private Instant creditedAt;
  private Instant createdAt;
}
