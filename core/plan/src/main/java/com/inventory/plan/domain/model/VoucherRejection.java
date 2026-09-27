package com.inventory.plan.domain.model;

/** Why a voucher cannot be used, returned to the client as {@code details.reason}. */
public enum VoucherRejection {
  NOT_FOUND,
  INACTIVE,
  /** Outside its validity window, including not yet valid. */
  EXPIRED,
  EXHAUSTED,
  WRONG_SHOP,
  NOT_APPLICABLE_TO_CART,
  ALREADY_REDEEMED
}
