package com.inventory.plan.domain.model;

/** Why an attribution was sent to review instead of resolving automatically. */
public enum ReferralReviewReason {
  /** Only a referrer name was given; names don't identify a shop. */
  NAME_ONLY,
  /** The code no longer matches any shop. */
  UNKNOWN_CODE,
  /** The person registering already owns the referrer shop. */
  SAME_OWNER,
  SAME_EMAIL,
  SAME_PHONE
}
