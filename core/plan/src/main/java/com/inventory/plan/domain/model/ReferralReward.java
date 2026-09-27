package com.inventory.plan.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Cashback owed to a referrer for a referee's first eligible plan purchase. Unique per order and per
 * referee, so a referee earns its referrer one reward at most. The percent is snapshotted.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "referral_rewards")
public class ReferralReward {

  @Id
  private String id;
  private String referrerShopId;
  private String refereeShopId;
  private String orderId;
  private String planCode;
  /** Plan line after vouchers and wallet credit: what the referee actually paid for the plan (§24). */
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
  private Instant updatedAt;
}
