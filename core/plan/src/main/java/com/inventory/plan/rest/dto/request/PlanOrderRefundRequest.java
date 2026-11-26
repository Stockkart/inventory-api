package com.inventory.plan.rest.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanOrderRefundRequest {

  /** Required; kept on the order and in the audit log. */
  private String reason;
  /**
   * Default true: refund what the gateway has not refunded yet. False when the money was already
   * returned outside the app (e.g. from the Razorpay dashboard).
   */
  private Boolean issueGatewayRefund;
}
