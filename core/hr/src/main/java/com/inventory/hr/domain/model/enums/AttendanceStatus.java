package com.inventory.hr.domain.model.enums;

/**
 * A stored day mark. Leave and Unmarked are never stored (derived on read); WEEK_OFF and HOLIDAY
 * are stored only when someone overrides a derived day.
 */
public enum AttendanceStatus {
  PRESENT,
  ABSENT,
  HALF_DAY,
  WEEK_OFF,
  HOLIDAY
}
