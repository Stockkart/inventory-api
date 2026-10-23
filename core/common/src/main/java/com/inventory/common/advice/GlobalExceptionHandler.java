package com.inventory.common.advice;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiError;
import com.inventory.common.dto.response.ApiErrorMapper;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for all controllers.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @Autowired
  private ApiErrorMapper apiErrorMapper;

  /** Named in the "upload too large" answer when the exception does not carry the limit. */
  @org.springframework.beans.factory.annotation.Value("${spring.servlet.multipart.max-request-size:10MB}")
  private org.springframework.util.unit.DataSize maxRequestSize;
  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleBaseException(BaseException ex, HttpServletRequest request) {
    if (ex.getErrorCode() == ErrorCode.INTERNAL_SERVER_ERROR && isBusy(ex, request)) {
      return busy(ex, request);
    }
    log.error("Business exception: {}", ex.getMessage(), ex);
    ErrorCode errorCode = ex.getErrorCode();
    ApiError apiError = apiErrorMapper.toApiError(ex.getMessage(), errorCode.getHttpStatus().value());
    ApiResponse<ApiError> response = ApiResponse.error(ex.getMessage());
    response.setData(apiError);
    return new ResponseEntity<>(response, errorCode.getHttpStatus());
  }

  /** Seconds a client is told to wait before retrying a "busy" (503) answer. */
  static final String BUSY_RETRY_AFTER_SECONDS = "2";

  /**
   * Request attribute set (by the app's Mongo pool listener) when this request found no free
   * database connection in time; lets the answer be 503 even when a service rethrew without the
   * driver's exception as cause.
   */
  public static final String MONGO_BUSY_ATTRIBUTE = "inventory.mongo.busy";

  private static boolean isBusy(Throwable ex, HttpServletRequest request) {
    return isBusy(ex) || (request != null && Boolean.TRUE.equals(request.getAttribute(MONGO_BUSY_ATTRIBUTE)));
  }

  /**
   * True when the request failed because MongoDB could not be reached in time: no free pooled
   * connection within {@code mongo.pool.max-wait-ms}, or no server selectable. The driver throws
   * {@code com.mongodb.MongoTimeoutException}; services usually wrap it, so the whole cause chain
   * is checked (by name: this module does not depend on the driver).
   */
  static boolean isBusy(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if ("com.mongodb.MongoTimeoutException".equals(t.getClass().getName())) {
        return true;
      }
    }
    return false;
  }

  /** 503 with Retry-After: overloaded for a moment, not broken. Logged without a stack trace. */
  private ResponseEntity<ApiResponse<ApiError>> busy(Throwable ex, HttpServletRequest request) {
    log.warn("Busy (no database connection in time) on {} {}: {}",
        request != null ? request.getMethod() : "-", request != null ? request.getRequestURI() : "-", ex.getMessage());
    String message = ErrorCode.SERVICE_BUSY.getMessage();
    ApiError apiError = apiErrorMapper.toApiError(message, HttpStatus.SERVICE_UNAVAILABLE.value());
    ApiResponse<ApiError> response = ApiResponse.error(message);
    response.setData(apiError);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, BUSY_RETRY_AFTER_SECONDS)
        .body(response);
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleValidationException(ValidationException ex, HttpServletRequest request) {
    log.error("Validation exception: {}", ex.getMessage(), ex);
    Map<String, String[]> errors = new HashMap<>();
    String[] errorMessages = ex.getValidationErrors().toArray(new String[0]);
    errors.put("general", errorMessages);

    String summary =
        ex.getValidationErrors().isEmpty()
            ? ex.getMessage()
            : String.join("; ", ex.getValidationErrors());

    ApiError apiError = apiErrorMapper.toApiError(summary, HttpStatus.BAD_REQUEST.value(), errors);
    ApiResponse<ApiError> response = ApiResponse.error(summary);
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
    log.error("Authentication exception: {}", ex.getMessage(), ex);
    ApiError apiError = apiErrorMapper.toApiError(ex.getMessage(), HttpStatus.UNAUTHORIZED.value());
    ApiResponse<ApiError> response = ApiResponse.error(ex.getMessage());
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleBadCredentialsException(BadCredentialsException ex, HttpServletRequest request) {
    log.error("Bad credentials: {}", ex.getMessage(), ex);
    ApiError apiError = apiErrorMapper.toApiError("Invalid username or password", HttpStatus.UNAUTHORIZED.value());
    ApiResponse<ApiError> response = ApiResponse.error("Invalid username or password");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
    log.error("Access denied: {}", ex.getMessage(), ex);
    ApiError apiError = apiErrorMapper.toApiError("You don't have permission to access this resource", HttpStatus.FORBIDDEN.value());
    ApiResponse<ApiError> response = ApiResponse.error("You don't have permission to access this resource");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleConstraintViolationException(ConstraintViolationException ex, HttpServletRequest request) {
    log.error("Constraint violation: {}", ex.getMessage(), ex);

    Map<String, List<String>> errorsMap = ex.getConstraintViolations().stream()
        .collect(Collectors.groupingBy(
            violation -> violation.getPropertyPath().toString(),
            Collectors.mapping(
                violation -> violation.getMessage(),
                Collectors.toList()
            )
        ));

    Map<String, String[]> errors = errorsMap.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> entry.getValue().toArray(new String[0])
        ));

    ApiError apiError = apiErrorMapper.toApiError("Validation failed", HttpStatus.BAD_REQUEST.value(), errors);
    ApiResponse<ApiError> response = ApiResponse.error("Validation failed");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<ApiError>> handleAllUncaughtException(Exception ex, HttpServletRequest request) {
    if (isBusy(ex, request)) {
      return busy(ex, request);
    }
    log.error("Unhandled exception: {}", ex.getMessage(), ex);
    ApiError apiError = apiErrorMapper.toApiError("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR.value());
    ApiResponse<ApiError> response = ApiResponse.error("An unexpected error occurred");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    log.error("Method argument not valid: {}", ex.getMessage(), ex);

    Map<String, List<String>> errorsMap = ex.getBindingResult()
        .getFieldErrors()
        .stream()
        .collect(Collectors.groupingBy(
            org.springframework.validation.FieldError::getField,
            Collectors.mapping(
                fieldError -> fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "",
                Collectors.toList()
            )
        ));

    Map<String, String[]> errors = errorsMap.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> entry.getValue().toArray(new String[0])
        ));

    ApiError apiError = apiErrorMapper.toApiError("Validation failed", HttpStatus.BAD_REQUEST.value(), errors);
    ApiResponse<ApiError> response = ApiResponse.error("Validation failed");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {

    log.error("Message not readable: {}", ex.getMessage(), ex);
    ApiError apiError = apiErrorMapper.toApiError("Malformed JSON request", HttpStatus.BAD_REQUEST.value());
    ApiResponse<ApiError> response = ApiResponse.error("Malformed JSON request");
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
      org.springframework.web.multipart.MaxUploadSizeExceededException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    log.warn("Upload too large: {}", ex.getMessage());
    long max = ex.getMaxUploadSize() > 0 ? ex.getMaxUploadSize()
        : maxRequestSize != null ? maxRequestSize.toBytes() : -1;
    String message =
        max > 0
            ? "Upload too large: the limit is " + (max / (1024 * 1024)) + " MB per upload"
            : "Upload too large";
    ApiError apiError = apiErrorMapper.toApiError(message, HttpStatus.PAYLOAD_TOO_LARGE.value());
    ApiResponse<ApiError> response = ApiResponse.error(message);
    response.setData(apiError);
    return new ResponseEntity<>(response, HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @ExceptionHandler({
    org.springframework.web.context.request.async.AsyncRequestNotUsableException.class,
    java.io.IOException.class
  })
  public void ignoreSseDisconnects() {
    log.info("Ignoring SSE Disconnect");
  }
}
