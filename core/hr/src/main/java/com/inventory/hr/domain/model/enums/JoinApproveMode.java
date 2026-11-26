package com.inventory.hr.domain.model.enums;

/** How an approver closes a worker's join request in the worker's favour (worker-app R4.4, R4.8). */
public enum JoinApproveMode {
  /** Create a new ACTIVE employee from the request snapshot and link it to the account. */
  CREATE,
  /** Link the account to an existing ACTIVE, unlinked employee of the shop (the possible match). */
  LINK
}
