package com.inventory.hr.domain.model.enums;

/** Why a pairing became {@link PairingStatus#UNPAIRED} (R2.15, R3). */
public enum UnpairReason {
  /** "Unpair phone" by a user with "add and edit employees" (R2.12, R3.1). */
  MANUAL,
  /** "Remove this phone" from the app's settings screen (R3.7). */
  WORKER,
  /** A new phone completed pairing for the same employee (R2.10). */
  REPLACED,
  /** The same installation completed pairing with another employee (R2.11). */
  PAIRED_TO_ANOTHER_EMPLOYEE,
  /** Automatic unpair on the employee's exit date (R3.4–R3.6). */
  EMPLOYEE_EXITED
}
