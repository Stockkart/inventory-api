package com.inventory.accounting.domain.model;

/**
 * Lifecycle of a fiscal (year, month) bucket. CLOSED rejects mutating writes from the regular
 * flows; LOCKED additionally forbids admin overrides.
 */
public enum PeriodStatus {
  OPEN,
  CLOSED,
  LOCKED
}
