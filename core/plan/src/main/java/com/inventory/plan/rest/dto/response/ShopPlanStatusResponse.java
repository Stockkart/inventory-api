package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

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
  /** Length of the free trial a new shop gets, in days ({@code plan.trial-days}). */
  private Integer trialDays;
  /** True when the shop may buy add-ons on their own now (an active paid plan, as add-on-only checkout requires). */
  private boolean canBuyAddOnsAlone;
  /**
   * True only when this shop could still start a free trial (it never had a plan or a trial); the
   * plan cards show the trial badge only then.
   */
  private boolean trialAvailable;
  /** What each catalogue plan's card offers this shop (current, upgrade or buy), in catalogue order. */
  private List<PlanOptionResponse> planOptions;
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
