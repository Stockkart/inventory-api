package com.inventory.hr.domain.model.enums;

/**
 * Where a punch came from. SELF (My HR) and BIOMETRIC_APP (the Attendance app) are written today;
 * DEVICE_FILE and BRIDGE stay reserved for biometric machines.
 */
public enum PunchSource {
  SELF,
  /** The Attendance app on a paired phone, signed after the phone's own biometric check (R5). */
  BIOMETRIC_APP,
  DEVICE_FILE,
  BRIDGE
}
