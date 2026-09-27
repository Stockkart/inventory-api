package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;
import lombok.Getter;

import java.util.Map;

/**
 * The shop's plan does not allow the action. {@link #details} lets the client show an upgrade prompt.
 */
@Getter
public class EntitlementException extends BaseException {

  private final Map<String, Object> details;

  public EntitlementException(ErrorCode errorCode, String message, Map<String, Object> details) {
    super(errorCode, message);
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }
}
