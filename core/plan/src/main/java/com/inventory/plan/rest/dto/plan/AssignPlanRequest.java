package com.inventory.plan.rest.dto.plan;

import lombok.Data;

@Data
public class AssignPlanRequest {

  private String planId;
  /** Duration in months (e.g. 1 for monthly). */
  private Integer durationMonths;
  /** Payment method: CARD, UPI, NET_BANKING, etc. */
  private String paymentMethod;
}
