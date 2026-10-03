package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;

/**
 * Thrown when a GST return cannot be built because the shop is not configured for it.
 *
 * <p>The case this exists for is a missing registered state. Interstate purchases are told apart
 * from local ones by comparing the supplier's state to the shop's, so a shop with neither a GSTIN
 * nor an address state silently reports every inward supply as intra-state — IGST credit lands
 * under CGST and SGST, and the return is wrong in a way nothing on screen reveals. Refusing to
 * generate is the lesser harm: an unfiled return is recoverable, a mis-filed one is not.
 */
public class GstConfigurationException extends BaseException {

  public GstConfigurationException(String message) {
    super(ErrorCode.GST_CONFIGURATION_MISSING, message);
  }
}
