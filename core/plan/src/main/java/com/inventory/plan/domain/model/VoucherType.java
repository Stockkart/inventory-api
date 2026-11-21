package com.inventory.plan.domain.model;

public enum VoucherType {
  /** The add-on units are free. */
  FREE_ADDON,
  /** {@code value} percent off the add-on line. */
  PERCENT_OFF,
  /** {@code value} rupees off the add-on line, never below zero. */
  FLAT_OFF
}
