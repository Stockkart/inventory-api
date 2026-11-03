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
  LOCKED_PERIOD
}
