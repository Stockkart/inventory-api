package com.inventory.plan.rest.dto.response;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A plan as the platform admin sees it, including hidden ones.
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class AdminPlanResponse extends PlanResponse {

  private boolean active;
}
