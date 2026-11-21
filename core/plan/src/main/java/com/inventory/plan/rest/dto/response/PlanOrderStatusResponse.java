package com.inventory.plan.rest.dto.response;

import com.inventory.plan.domain.model.OrderPhase;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A plan order's status after a cancel. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlanOrderStatusResponse {

  private String orderId;
  /** Raw order status, e.g. CANCELLED. */
  private String status;
  /** What the status means for the buyer; see {@link OrderPhase}. */
  private OrderPhase phase;
}
