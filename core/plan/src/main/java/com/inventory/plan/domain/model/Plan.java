package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.List;

/**
 * Plan master entity. Plans form a linked list via linkedId pointing to the next higher plan.
 *
 * <p>The collection holds subscription tiers, add-ons and OCR top-up packs; {@link #kind}
 * distinguishes them. Catalog fields are nullable so documents written before they existed continue
 * to load, and clients fall back to the previous behaviour when they are absent.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "plans")
public class Plan {

  @Id
  private String id;
  private String planName;
  /** One-time service/support fee (annual amount). Used when customer needs first-time service help. */
  private BigDecimal price;
  /** Annual subscription price (per year). This is what customers pay for the plan. */
  private BigDecimal arcPrice;
  /** Monthly billing amount cap in rupees. null = unlimited. */
  private BigDecimal billingLimit;
  /** Maximum bill count per month. null = unlimited. */
  private Integer billCountLimit;
  /** SMS limit per month. 0 = not included. null = unlimited. */
  private Integer smsLimit;
  /** WhatsApp message limit per month. 0 = not included. null = unlimited. */
  private Integer whatsappLimit;
  /** Number of users allowed per shop. null = flexible. */
  private Integer userLimit;
  /** When true, billing/SMS/WhatsApp are unlimited. */
  private boolean unlimited;
  /** ID of the next higher plan (upsell target). Null for top plan. */
  private String linkedId;
  private String bestFor; // e.g. "Small businesses with limited billing"

  /** What this row is. Treated as {@link PlanKind#PLAN} when null. */
  private PlanKind kind;

  /** Tier identity. Null for add-ons and top-ups. */
  private PlanTier tier;

  /** Ladder position, ascending. Drives ordering and upgrade comparisons. */
  private Integer tierRank;

  /** Roles this tier covers, e.g. ["Owner", "Manager", "Cashier"]. */
  private List<String> userRoles;

  /** OCR invoices included per month. null = none. */
  private Integer ocrInvoiceLimit;

  /** Invoices granted by a one-off pack. Only meaningful when {@link #kind} is OCR_TOPUP. */
  private Integer ocrTopupInvoices;

  /** How this row is charged. Treated as {@link PlanBillingPeriod#YEAR} when null. */
  private PlanBillingPeriod billingPeriod;

  /** Unit a per-unit price multiplies, e.g. "user" renders "₹500/user/year". null = flat pricing. */
  private String perUnitLabel;

  /** Plans this add-on may attach to. null or empty = any plan. */
  private List<String> appliesToPlanIds;

  /** Capability list backing the pricing comparison matrix. */
  private List<PlanFeature> features;

  /** Free days granted on first subscribe. Falls back to {@code plan.trial-days} when null. */
  private Integer trialDays;
}
