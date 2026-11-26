package com.inventory.hr.domain.model.enums;

/** How a {@code hr_join_requests} row was closed (worker-app R4.8–R4.11, R14.2). */
public enum JoinOutcome {
  /** Approved with "Create new": one employee was created from the request snapshot. */
  APPROVED_CREATED,
  /** Approved with "Link to existing": an existing employee's login link was set. */
  APPROVED_LINKED,
  REJECTED,
  WITHDRAWN
}
