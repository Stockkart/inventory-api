package com.inventory.credit.api;

public enum CreditEntryType {
  CHARGE,
  SETTLEMENT,
  /** Credit balance reduced by a sales or purchase return (no extra cash JE). */
  RETURN,
  ADJUSTMENT
}
