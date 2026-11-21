package com.inventory.product.rest.dto.request;

import com.inventory.product.domain.model.enums.PrintDocumentSource;
import lombok.Data;

/** Ask the backend how to print one document on the dot-matrix printer. */
@Data
public class CreatePrintJobRequest {
  private PrintDocumentSource source;
  /** The purchase, refund or vendor return id, per {@link #source}. */
  private String documentId;
  /** The bridge as the browser found it just now. */
  private PrintBridgeObservation bridge;
  /**
   * How long ago this browser sent this document to the bridge without seeing it finish, in
   * milliseconds. Null when it has no such job. The browser keeps that record; this service
   * decides what it means.
   */
  private Long pendingAgeMs;
}
