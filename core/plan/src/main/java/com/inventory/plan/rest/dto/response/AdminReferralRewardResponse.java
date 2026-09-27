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
public class AdminReferralRewardResponse {
  private String id;
  private String referrerShopId;
  private String referrerShopName;
  private String refereeShopId;
  private String refereeShopName;
  private String orderId;
  private String planCode;
  private BigDecimal basePlanAmount;
  private BigDecimal rewardPercent;
  private BigDecimal rewardAmount;
  private ReferralRewardStatus status;
  private Instant holdUntil;
  private Instant approvedAt;
  private Instant creditedAt;
  private Instant voidedAt;
  private Instant clawedBackAt;
  private String voidReason;
  private Instant createdAt;
}
