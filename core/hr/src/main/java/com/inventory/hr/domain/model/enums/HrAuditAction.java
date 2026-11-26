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
  ROLE_PERMISSIONS_CHANGED,
  /** A pairing code was created for an employee (by a manager or "self"). */
  PAIRING_CODE_CREATED,
  /** A phone finished pairing: after holds phoneModel, os, pairedBy, consentVersion. */
  DEVICE_PAIRED,
  /** A new phone replaced the employee's paired one: before/after hold the phone models. */
  DEVICE_REPLACED,
  /** A pairing ended: before holds phoneModel; reason is the {@code UnpairReason}. */
  DEVICE_UNPAIRED,
  /** The worker agreed to a newer consent version on an existing pairing. */
  DEVICE_CONSENT_UPDATED,
  /**
   * A worker's join or link request was approved (worker-app R4.11): entity {@code
   * WorkerJoinRequest}, employeeId = the created or linked employee, after holds the outcome and
   * mode.
   */
  JOIN_REQUEST_APPROVED,
  /** A worker's join or link request was rejected (worker-app R4.11): reason holds the text given. */
  JOIN_REQUEST_REJECTED,
  /**
   * A signed-in worker tried a pairing code issued for another employee (worker-app R7.3): written
   * on the code's employee; after holds {@code attemptedByUserId}.
   */
  PAIRING_CODE_REFUSED,
  /** A manager asked a worker for their profile details (worker-app R8.7). */
  PROFILE_REQUESTED
}
