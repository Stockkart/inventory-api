package com.inventory.plan.domain.model;

import com.inventory.common.entitlement.PlanFeature;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Plan master entity. Plans form a linked list via linkedId pointing to the next higher plan.
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

  /** Stable catalogue key, e.g. STARTER. Null on legacy rows. */
  @Indexed(unique = true, sparse = true)
  private String code;
  /** Pricing-page position, ascending. Null sorts after ordered plans. */
  private Integer displayOrder;
  /** False hides the plan from the catalogue. Null is treated as active so legacy rows stay visible. */
  private Boolean active;
  /** OCR invoices included per month. Null = not metered. */
  private Integer ocrLimit;
  /**
   * Gated capabilities this plan includes. Stored as "entitlements" because some environments hold
   * rows whose "features" field is a list of display objects.
   */
  @Field("entitlements")
  private Set<PlanFeature> features;
  /** Marketing highlight such as MOST_POPULAR. Null = no badge. */
  private String badge;
}
