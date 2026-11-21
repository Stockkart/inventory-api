package com.inventory.hr.domain.model.enums;

/** Where a punch came from. Only SELF is written today; the others are reserved for biometric. */
public enum PunchSource {
  SELF,
  DEVICE_FILE,
  BRIDGE
}
