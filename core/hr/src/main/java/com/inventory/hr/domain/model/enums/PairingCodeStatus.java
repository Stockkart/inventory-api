package com.inventory.hr.domain.model.enums;

/** Lifecycle of a one-time pairing code ({@code hr_pairing_codes}, R2.3–R2.5). */
public enum PairingCodeStatus {
  /** Created and not yet claimed by any phone. At most one per employee (R2.4). */
  UNUSED,
  /** A phone sent the code; names shown, consent pending. Refused to any other phone. */
  CLAIMED,
  /** Pairing completed with this code. The document is then deleted (R9.11). */
  USED,
  /** Replaced by a newer code for the same employee (R2.4). */
  CANCELLED,
  /** Worker tapped "No" / "Don't agree" (R2.6, R2.8) or the 11th attempt hit it (R9.10). */
  INVALIDATED
}
