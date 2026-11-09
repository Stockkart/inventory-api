package com.inventory.hr.domain.model.enums;

/** What self check-in does when the phone is outside the shop radius or has no location. */
public enum OutsideAction {
  /** Refuse the punch (default). */
  BLOCK,
  /** Keep the punch and flag it. */
  FLAG
}
