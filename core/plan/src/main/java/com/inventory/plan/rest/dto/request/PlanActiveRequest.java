package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanActiveRequest {

  private Boolean active;
  /** Why the plan was shown or hidden; kept in the audit log. */
  private String reason;
}
