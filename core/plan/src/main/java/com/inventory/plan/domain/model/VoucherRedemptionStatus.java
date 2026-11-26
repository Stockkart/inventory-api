package com.inventory.plan.domain.model;

/** RESERVED at checkout, REDEEMED at fulfilment, RELEASED when the order fails or expires (§27.3). */
public enum VoucherRedemptionStatus {
  RESERVED,
  REDEEMED,
  RELEASED
}
