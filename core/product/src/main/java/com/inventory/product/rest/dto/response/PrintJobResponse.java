package com.inventory.product.rest.dto.response;

import com.inventory.documentservice.api.DotMatrixDocumentKind;
import com.inventory.product.domain.model.enums.PrintAction;
import com.inventory.product.domain.model.enums.PrintActionReason;
import com.inventory.product.domain.model.enums.PrintBridgeState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * How to print one document. The browser follows {@link #action}: BRIDGE sends {@link
 * #bridgeRequest} as it is and reports the result; DOWNLOAD hands over {@link #download};
 * IN_PROGRESS sends nothing.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PrintJobResponse {
  private PrintAction action;
  /** Null when the job goes to the bridge. */
  private PrintActionReason reason;
  private PrintBridgeState bridgeState;
  private DotMatrixDocumentKind documentKind;
  /** The number printed on the document, e.g. T001148, for messages about it later. */
  private String documentNumber;
  /** Present when {@link #action} is BRIDGE. */
  private PrintBridgeJobPayload bridgeRequest;
  /**
   * Present for BRIDGE as well as DOWNLOAD: if the bridge turns out to be unreachable when the
   * job is sent, the outcome says to fall back to this file.
   */
  private PrintDownload download;
  private PrintPollPolicy poll;
  /**
   * How long the browser treats this document as on its way once sent: a second print within
   * this is IN_PROGRESS, and a job not seen to finish by then is reported UNCONFIRMED.
   */
  private long inFlightTtlMs;
}
