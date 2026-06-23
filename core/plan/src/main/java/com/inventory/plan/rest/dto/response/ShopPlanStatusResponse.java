package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopPlanStatusResponse {

  private String shopId;
  private String planId;
  private PlanResponse plan;
  private Instant planExpiryDate;
  private boolean trial;
  private boolean trialExpired;
  /** True when planExpiryDate is in the past (trial or paid subscription). */
  private boolean planExpired;
  private UsageResponse currentUsage;
  private PlanResponse suggestedPlan;
  private boolean billingLimitReached;
  private boolean billCountLimitReached;
  private boolean smsLimitReached;
  private boolean whatsappLimitReached;
  private boolean userLimitReached;
}
