package com.inventory.hr.domain.model.enums;

/** Where a stored day mark came from. */
public enum AttendanceSource {
  MANUAL,
  BULK_PRESENT,
  /**
   * Derived from the employee's own check-in punches ({@code hr_punches}). Rewritten by every new
   * punch of the day; a hand-made mark (MANUAL, BULK_PRESENT or null) is never touched by it.
   */
  SELF_CHECK_IN,
  /**
   * Derived like SELF_CHECK_IN, but the day's earliest counted punch came from the Attendance app
   * (R8.1–R8.2). Same rewrite and hand-made-mark rules.
   */
  BIOMETRIC_APP;

  /**
   * A day worked out from punches (SELF_CHECK_IN or BIOMETRIC_APP), as opposed to a hand-made mark
   * (MANUAL, BULK_PRESENT or a mark stored before sources existed, i.e. null). A hand-made mark
   * always wins over punches (R7.9).
   */
  public static boolean isDerived(AttendanceSource source) {
    return source == SELF_CHECK_IN || source == BIOMETRIC_APP;
  }
}
