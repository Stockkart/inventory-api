package com.inventory.product.rest.dto.response;

import com.inventory.documentservice.domain.DotMatrixDocumentKind;
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
  private String printJobId;
  private PrintAction action;
  /** Null when the job goes to the bridge. */
  private PrintActionReason reason;
  private PrintBridgeState bridgeState;
  private DotMatrixDocumentKind documentKind;
  /** Present when {@link #action} is BRIDGE. */
  private PrintBridgeJobPayload bridgeRequest;
  /**
   * Present for BRIDGE as well as DOWNLOAD: if the bridge turns out to be unreachable when the
   * job is sent, the outcome says to fall back to this file.
   */
  private PrintDownload download;
  private PrintPollPolicy poll;
}
