package com.inventory.hr.domain.model.enums;

/** Lifecycle of one {@code hr_join_requests} row (worker-app R3–R4). */
public enum WorkerJoinStatus {
  /** Waiting in the approvers' inbox; the one open request an account may have. */
  PENDING,
  /**
   * Internal: locked by an approval transaction. Never visible after commit (the transaction ends in
   * APPROVED or rolls back to PENDING); a sweep resets any row left here for over 5 minutes.
   */
  APPROVING,
  /** Decided in the worker's favour; {@code outcome} says whether an employee was created or linked. */
  APPROVED,
  /** Turned down by an approver, with an optional reason the worker sees word for word. */
  REJECTED,
  /** Taken back by the worker from the Waiting screen. */
  WITHDRAWN
}
