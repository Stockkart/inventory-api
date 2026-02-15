package com.inventory.product.domain.model;

/**
 * Type of scheme: fixed free units or percentage extra free.
 */
public enum SchemeType {
  /** Scheme as fixed free units (use scheme field) */
  FIXED_UNITS,
  /** Scheme as percentage extra free (use schemePercentage field, e.g. 10 = 10%) */
  PERCENTAGE
}
