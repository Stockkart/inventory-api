package com.inventory.plan.rest.dto.request;

import lombok.Data;

@Data
public class CreatePlanCheckoutRequest {

  private String planId;
  /** Duration in months. Defaults to 12 when omitted. */
  private Integer durationMonths;
}
