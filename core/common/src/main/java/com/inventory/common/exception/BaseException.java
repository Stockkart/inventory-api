package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;
import lombok.Getter;

/**
 * Base exception class for all custom exceptions in the application.
 */
@Getter
public class BaseException extends RuntimeException {
  private final ErrorCode errorCode;
  private final Object[] args;

  public BaseException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
    this.args = new Object[0];
  }

  public BaseException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.errorCode = errorCode;
    this.args = new Object[0];
  }

  public BaseException(ErrorCode errorCode, String message, Object... args) {
    super(message);
    this.errorCode = errorCode;
    this.args = args;
  }

  public BaseException(ErrorCode errorCode, String message, Throwable cause, Object... args) {
    super(message, cause);
    this.errorCode = errorCode;
    this.args = args;
  }
}
