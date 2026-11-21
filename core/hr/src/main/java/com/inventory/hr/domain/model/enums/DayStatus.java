package com.inventory.hr.domain.model.enums;

/**
 * The status an employee-day shows after {@code DayStatusResolver} (R5.3, R5.4, R5.13). Unlike
 * {@link AttendanceStatus} it is never stored: LEAVE, UNMARKED and NOT_EMPLOYED are derived on
 * read, and WEEK_OFF / HOLIDAY come from settings unless somebody stored an override.
 */
public enum DayStatus {
  PRESENT,
  ABSENT,
  HALF_DAY,
  LEAVE,
  WEEK_OFF,
  HOLIDAY,
  UNMARKED,
  /** Before the join date or after the exit date; not part of the register's totals. */
  NOT_EMPLOYED;

  public static DayStatus of(AttendanceStatus stored) {
    return DayStatus.valueOf(stored.name());
  }
}
