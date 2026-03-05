package com.inventory.plan.config;

import com.inventory.plan.domain.model.Plan;

import java.math.BigDecimal;

/**
 * Plan defaults for trial shops (when plan is not yet purchased).
 * Used when PlanDataSeeder is removed - data is managed via admin panel.
 */
public final class PlanDefaults {

  public static final int TRIAL_DAYS = 30;

  /** Billing limits for Base plan (trial defaults). */
  public static final BigDecimal BASE_BILLING_LIMIT = new BigDecimal("150000");
  public static final int BASE_BILL_COUNT = 450;

  private PlanDefaults() {}

  public static Plan getBasePlanDefaults() {
    Plan p = new Plan();
    p.setPlanName("Base");
    p.setBillingLimit(BASE_BILLING_LIMIT);
    p.setBillCountLimit(BASE_BILL_COUNT);
    p.setSmsLimit(0);
    p.setWhatsappLimit(0);
    p.setUserLimit(1);
    p.setUnlimited(false);
    return p;
  }
}
