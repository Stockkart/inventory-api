package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;
import java.util.Map;
import lombok.Getter;

/**
 * A business error the client acts on by its {@link ErrorCode} name and {@link #details}, both
 * returned in the error body.
 */
@Getter
public class DetailedException extends BaseException {

  private final Map<String, Object> details;

  public DetailedException(ErrorCode errorCode, String message, Map<String, Object> details) {
    super(errorCode, message);
    this.details = details == null ? Map.of() : Map.copyOf(details);
  }
}
