package com.inventory.plan.domain.model;

/** Reward lifecycle (§11): PENDING → APPROVED → CREDITED, or VOID / CLAWED_BACK. */
public enum ReferralRewardStatus {
  /** Recorded at fulfilment, inside the hold period. */
  PENDING,
  /** Cleared for crediting (by an operator, or when the hold ends). */
  APPROVED,
  /** Claimed by the hold-release job; the wallet credit is in progress. */
  CREDITING,
  CREDITED,
  /** Never credited: refunded in time, cap reached, or rejected. */
  VOID,
  /** Credited, then taken back after a refund. */
  CLAWED_BACK
}
