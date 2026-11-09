package com.inventory.hr.domain.model.enums;

/** Where a stored day mark came from. */
public enum AttendanceSource {
  MANUAL,
  BULK_PRESENT,
  /**
   * Derived from the employee's own check-in punches ({@code hr_punches}). Rewritten by every new
   * punch of the day; a hand-made mark (MANUAL, BULK_PRESENT or null) is never touched by it.
   */
  SELF_CHECK_IN
}
