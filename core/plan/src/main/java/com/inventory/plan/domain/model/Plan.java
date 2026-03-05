package com.inventory.plan.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;

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
  private BigDecimal price;
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
}
