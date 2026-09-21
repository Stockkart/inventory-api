package com.inventory.plugins.cafe.domain;

/** A tab's lifecycle. Ends only by explicit close — no expiry, no rollover, no eviction. */
public enum CafeTabStatus {
  OPEN,
  CLOSED
}
