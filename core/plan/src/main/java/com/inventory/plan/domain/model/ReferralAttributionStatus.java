package com.inventory.plan.domain.model;

/** Where a referee's attribution stands (§11). Only RESOLVED can earn a reward automatically. */
public enum ReferralAttributionStatus {
  /** The code matched a different party's shop. */
  RESOLVED,
  /** A person must decide: name only, unknown code, or the referrer looks like the same owner. */
  PENDING_REVIEW,
  /** Rejected: referee and referrer are the same shop. */
  SELF_REFERRAL,
  /** Rejected: the referee already has an attribution. */
  DUPLICATE,
  /** Rejected by an operator, with a reason. */
  REJECTED
}
