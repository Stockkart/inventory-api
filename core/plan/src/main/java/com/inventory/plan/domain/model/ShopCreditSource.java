package com.inventory.plan.domain.model;

/** What moved money in a shop's wallet (§12). */
public enum ShopCreditSource {
  REFERRAL_REWARD,
  /** Checkout held credit for an order: available → reserved. */
  ORDER_RESERVATION,
  /** The order ended unpaid: reserved → outstanding clawback first, then available. */
  RESERVATION_RELEASE,
  /** The order was paid: reserved credit is spent. */
  ORDER_REDEMPTION,
  MANUAL_ADJUSTMENT,
  CLAWBACK
}
