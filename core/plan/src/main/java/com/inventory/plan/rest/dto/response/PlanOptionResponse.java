package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.PlanAction;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What one catalogue plan's card offers the shop on {@code GET /api/v1/plans/shop/status}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanOptionResponse {

  private String planId;
  private String planCode;
  private PlanAction action;
  /**
   * False when quote and checkout would refuse the plan now: a different plan while the paid plan
   * still has days left ({@code PLAN_CHANGE_NOT_ALLOWED}).
   */
  private boolean actionAvailable;
  /** Why the action is not available, shown as is; null when it is. */
  private String unavailableReason;
  /** When the action becomes available (the paid plan's expiry); null when it already is. */
  private Instant availableFrom;
}
