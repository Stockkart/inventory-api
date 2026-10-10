package com.inventory.plan.domain.model;

public enum SubscriptionStatus {
  /** No paid plan yet; measured against the trial plan until expiresAt. */
  TRIAL,
  ACTIVE,
  EXPIRED,
  CANCELLED
}
