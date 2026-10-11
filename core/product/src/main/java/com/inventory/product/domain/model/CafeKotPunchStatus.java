package com.inventory.product.domain.model;

/** A punch exists only once its deltas are durable, so there is no ambiguous state. */
public enum CafeKotPunchStatus {
  PENDING_KOT_CREATION,
  COMPLETE
}
