package com.inventory.product.domain.model.enums;

/** The situation a reported print is in. Codes, not sentences: the frontend owns the wording. */
public enum PrintOutcome {
  PRINTED,
  FAILED_PRINTER,
  STILL_QUEUED,
  /** The bridge was not reachable; download the printer file instead. */
  BRIDGE_UNREACHABLE,
  /** The bridge already had this document in flight; nothing more to do. */
  ALREADY_SENT,
  /** The bridge refused the job or its reply could not be confirmed. It may have printed. */
  BRIDGE_REJECTED
}
