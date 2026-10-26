package com.inventory.hr.domain.model.enums;

/** Payroll run state; FINALISING and REOPENING are short-lived claims (design D8). */
public enum PayrollRunStatus {
  DRAFT,
  FINALISING,
  FINALISED,
  REOPENING
}
