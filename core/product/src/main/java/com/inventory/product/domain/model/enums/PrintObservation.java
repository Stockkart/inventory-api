package com.inventory.product.domain.model.enums;

/**
 * What the browser saw when it sent a job to the bridge. An observation, not a conclusion: the
 * backend decides what it means for the job and what the operator is told.
 */
public enum PrintObservation {
  /** The bridge's job history shows PRINTED. */
  PRINTED,
  /** The bridge's job history shows FAILED, with the printer's error. */
  FAILED,
  /** Polling ran out while the bridge still showed the job QUEUED. */
  STILL_QUEUED,
  /** The bridge could not be contacted at all; nothing reached the printer. */
  UNREACHABLE,
  /** The bridge answered 409: it already had this document in flight. */
  DUPLICATE,
  /** The bridge answered with any other refusal, or its reply could not be confirmed. */
  REJECTED
}
