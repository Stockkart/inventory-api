package com.inventory.plan.domain.model;

/** What buying one unit of an add-on gives the shop. */
public enum AddOnGrantType {
  /** Unlocks {@code grantsFeature} for the term. */
  FEATURE,
  /** Raises the user limit by {@code grantsQuantity} for the term. */
  SEATS,
  /** Raises the SMS quota by {@code grantsQuantity} for the term. */
  SMS,
  /** {@code grantsQuantity} invoice scans, used after the monthly quota. Never expire. */
  OCR_CREDITS
}
