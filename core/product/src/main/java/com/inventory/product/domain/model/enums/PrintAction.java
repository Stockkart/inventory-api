package com.inventory.product.domain.model.enums;

/** What the browser is to do with a print job. The browser never chooses this itself. */
public enum PrintAction {
  /** Send {@code bridgeRequest} to the bridge as it is, then report what the bridge said. */
  BRIDGE,
  /** No bridge: hand the operator the printer file in {@code download}. */
  DOWNLOAD,
  /** This browser already sent the same document and has not seen it finish: send nothing. */
  IN_PROGRESS
}
