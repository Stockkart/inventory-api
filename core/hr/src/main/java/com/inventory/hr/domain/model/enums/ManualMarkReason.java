package com.inventory.hr.domain.model.enums;

/**
 * Quick picks a manager chooses when marking a paired employee's day by hand while the biometric
 * app is on for the shop (R7.3). {@link #OTHER} requires a free-text reason of 1–200 characters.
 */
public enum ManualMarkReason {
  FORGOT_PHONE,
  PHONE_NOT_WORKING,
  NO_NETWORK,
  BIOMETRIC_NOT_WORKING,
  OTHER
}
