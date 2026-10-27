package com.inventory.hr.domain.model.enums;

/** Payment state of a payroll line; PAYING and UNDOING are short-lived claims. */
public enum SalaryPaymentStatus {
  PENDING,
  PAYING,
  PAID,
  UNDOING
}
