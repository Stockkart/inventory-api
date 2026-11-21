package com.inventory.hr.domain.model.enums;

/** Marks on a stored punch that managers see on the register. */
public enum PunchFlag {
  /** Farther from the shop than the radius (location check, FLAG mode). */
  OUTSIDE_SHOP,
  /** Reported accuracy worse than 200 m, or none (FLAG mode). */
  LOW_ACCURACY,
  /** The browser gave no location (FLAG mode). */
  NO_LOCATION,
  /** Reserved: a punch in a finalised salary month (self check-in refuses those instead). */
  LOCKED_PERIOD,
  /**
   * The phone marked its location reading as mock (faked); the only flag on such a punch and its
   * distance is dropped (FLAG mode, R4.6). Attendance app only.
   */
  FAKE_LOCATION,
  /** Saved on the phone while offline and sent later; {@code at} is the phone's time (R6.9). */
  RECORDED_OFFLINE
}
