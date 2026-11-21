package com.inventory.plan.rest.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VerifyPlanPaymentResponse {

  /** True only when the order is FULFILLED (the plan is active). */
  private boolean success;
  private String orderId;
  /** Order status after verification; FULFILLING or PAID means the plan is still being applied. */
  private String status;
  /** What the status means for the buyer (PROCESSING = payment received, confirming). */
  private com.inventory.plan.domain.model.OrderPhase phase;
  private PlanResponse plan;
}
