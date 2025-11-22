package com.inventory.common.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.inventory.common.constants.ErrorCode;
import lombok.Data;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standard error response for all API errors.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
  private final LocalDateTime timestamp;
  private final int status;
  private final String error;
  private final int code;
  private final String message;
  private final String path;
  private final List<FieldError> errors;

  public ErrorResponse(LocalDateTime timestamp, int status, String error, int code,
                       String message, String path, List<FieldError> errors) {
    this.timestamp = timestamp;
    this.status = status;
    this.error = error;
    this.code = code;
    this.message = message;
    this.path = path;
    this.errors = errors;
  }

  public static ErrorResponse of(HttpStatus status, ErrorCode errorCode, String message, String path) {
    return new ErrorResponse(
            LocalDateTime.now(),
            status.value(),
            status.getReasonPhrase(),
            errorCode.getCode(),
            message,
            path,
            null
    );
  }

  @Data
  public static class FieldError {
    private final String field;
    private final String message;
    private final Object rejectedValue;

    public FieldError(String field, String message, Object rejectedValue) {
      this.field = field;
      this.message = message;
      this.rejectedValue = rejectedValue;
    }
  }
}
