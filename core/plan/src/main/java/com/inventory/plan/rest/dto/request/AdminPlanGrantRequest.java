package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An admin plan grant: a plan for some months without payment. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminPlanGrantRequest {

  private String planCode;
  /** 1 to 36. */
  private Integer durationMonths;
  /** Required; kept on the audit entry. */
  private String reason;
}
