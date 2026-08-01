package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.PlanBillingPeriod;
import com.inventory.plan.domain.model.PlanKind;
import com.inventory.plan.domain.model.PlanTier;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Plan catalog row. Covers subscription tiers, add-ons and OCR top-ups; {@link #kind} distinguishes
 * them so clients never match on {@link #planName}.
 *
 * <p>Catalog fields are nullable — a shop whose plans predate them still serialises cleanly, and
 * clients treat absence as "fall back to previous behaviour" rather than as an error.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanResponse {

  private String id;
  private String planName;
  private BigDecimal price;
  private BigDecimal arcPrice;
  private BigDecimal billingLimit;
  private Integer billCountLimit;
  private Integer smsLimit;
  private Integer whatsappLimit;
  private Integer userLimit;
  private boolean unlimited;
  /** ID of the next higher plan (upsell target). Null for the top tier. */
  private String linkedId;
  private String bestFor;

  private PlanKind kind;
  private PlanTier tier;
  private Integer tierRank;
  private List<String> userRoles;
  private Integer ocrInvoiceLimit;
  private Integer ocrTopupInvoices;
  private PlanBillingPeriod billingPeriod;
  private String perUnitLabel;
  private List<String> appliesToPlanIds;
  private List<PlanFeatureResponse> features;
  private Integer trialDays;
}
