package com.inventory.product.domain.model.enums;

import java.util.List;

/**
 * Where a print job stands, as far as StockKart knows. Physical printing is at-least-once: paper
 * can come out and the acknowledgement still be lost, so no status here means "did not print"
 * unless the printer itself said so.
 */
public enum PrintJobStatus {
  /** Handed to the browser to send to the bridge; nothing reported yet. */
  PENDING,
  /** The bridge accepted it and was still printing when the browser stopped watching. */
  SUBMITTED,
  /** The bridge reported the printer took it. */
  PRINTED,
  /** The bridge reported the printer failed it (off, out of paper, jammed). */
  FAILED_PRINTER,
  /** The bridge answered but refused the job. */
  FAILED_BRIDGE,
  /** The browser could not reach the bridge when sending; the printer file was handed over. */
  FAILED_CLIENT,
  /** No bridge was detected; the printer file was handed over. Not proof anyone printed it. */
  DOWNLOAD_READY,
  /** The bridge had the same document in flight and printed it once. */
  DUPLICATE_SUPPRESSED,
  /**
   * Nothing was reported in time. The outcome is unknown, not negative: the bridge may well
   * have printed it before the browser closed.
   */
  EXPIRED;

  /** Statuses during which a second job for the same document would print it twice. */
  public static final List<PrintJobStatus> IN_FLIGHT = List.of(PENDING, SUBMITTED);

  public boolean isInFlight() {
    return IN_FLIGHT.contains(this);
  }
}
