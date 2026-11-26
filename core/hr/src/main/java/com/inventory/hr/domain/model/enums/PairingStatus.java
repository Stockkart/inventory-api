package com.inventory.hr.domain.model.enums;

/** Lifecycle of one Attendance app pairing ({@code hr_device_pairings}, R2.15). */
public enum PairingStatus {
  /** Pairing completed and not yet unpaired; the phone's punches are accepted (R5.7). */
  ACTIVE,
  /** Unpaired by any route in R3 or displaced (R2.10, R2.11); every punch is refused (R3.2). */
  UNPAIRED
}
