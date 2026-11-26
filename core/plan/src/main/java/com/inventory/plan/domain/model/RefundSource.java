package com.inventory.plan.domain.model;

/** Where a refund came from (r4.6). All converge on OrderRefundService. */
public enum RefundSource {
  /** The gateway reported refunds covering the whole order. */
  GATEWAY_REFUND,
  /** The customer won a chargeback. */
  GATEWAY_DISPUTE,
  /** A platform admin refunded the order. */
  ADMIN
}
