package com.inventory.plan.rest.dto.request;

import lombok.Data;

@Data
public class AssignPlanRequest {

  private String planId;
  /** Duration in months (e.g. 1 for monthly). */
  private Integer durationMonths;
  /** Payment method: CARD, UPI, NET_BANKING, etc. */
  private String paymentMethod;
  /** Internal checkout order id. */
  private String paymentOrderId;
  private String provider;
  private String providerPaymentId;
  private String providerOrderId;
}
