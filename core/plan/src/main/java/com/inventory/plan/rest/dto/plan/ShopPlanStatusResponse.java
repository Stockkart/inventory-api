package com.inventory.plan.rest.dto.plan;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShopPlanStatusResponse {

  private String shopId;
  private String planId;
  private PlanResponse plan;
  private Instant expiryDate;
  private boolean trial;
  private boolean trialExpired;
  private UsageResponse currentUsage;
  private PlanResponse suggestedPlan; // Next higher plan for upsell
  private boolean billingLimitReached;
  private boolean billCountLimitReached;
  private boolean smsLimitReached;
  private boolean whatsappLimitReached;
  private boolean userLimitReached;
}
