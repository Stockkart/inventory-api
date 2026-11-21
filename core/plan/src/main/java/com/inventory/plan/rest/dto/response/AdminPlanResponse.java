package com.inventory.plan.rest.dto.response;

import java.math.BigDecimal;
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
  /** Floor for the plan line after wallet credit. Admin only: shops never see it. */
  private BigDecimal basePrice;
}
