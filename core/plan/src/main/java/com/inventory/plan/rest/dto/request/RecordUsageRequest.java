package com.inventory.plan.rest.dto.request;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class RecordUsageRequest {

  /** Billing amount to add. */
  private BigDecimal billingAmount;
  /** Bill count to add (typically 1). */
  private Integer billCount;
  /** SMS count to add. */
  private Integer smsCount;
  /** WhatsApp count to add. */
  private Integer whatsappCount;
}
