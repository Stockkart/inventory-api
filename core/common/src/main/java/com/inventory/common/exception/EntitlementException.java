package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;

import java.util.Map;

/**
 * The shop's plan does not allow the action. {@link #getDetails()} lets the client show an upgrade prompt.
 */
public class EntitlementException extends DetailedException {

  public EntitlementException(ErrorCode errorCode, String message, Map<String, Object> details) {
    super(errorCode, message, details);
  }
}
