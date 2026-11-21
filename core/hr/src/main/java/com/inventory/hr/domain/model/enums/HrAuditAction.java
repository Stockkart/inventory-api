package com.inventory.hr.domain.model.enums;

/** What an {@code hr_audit_events} row records (R14.7). */
public enum HrAuditAction {
  ATTENDANCE_SET,
  ATTENDANCE_BULK_SET,
  LEAVE_APPROVED,
  LEAVE_REJECTED,
  LEAVE_CANCELLED,
  PAYROLL_FINALISED,
  PAYROLL_REOPENED,
  SALARY_PAID,
  SALARY_PAYMENT_UNDONE,
  ADVANCE_RECORDED,
  ADVANCE_CANCELLED,
  EMPLOYEE_CHANGED,
  /** Login link set, changed or cleared: before/after hold {@code linked} yes/no + userId. */
  EMPLOYEE_LINK_CHANGED,
  /** Email changed: after holds only {@code emailChanged: true}, never the address. */
  EMPLOYEE_EMAIL_CHANGED,
  SETTINGS_CHANGED,
  ROLE_PERMISSIONS_CHANGED
}
