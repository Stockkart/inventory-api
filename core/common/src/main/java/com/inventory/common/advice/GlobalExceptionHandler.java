package com.inventory.common.advice;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ErrorResponse;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Global exception handler for all controllers.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ErrorResponse> handleBaseException(BaseException ex, HttpServletRequest request) {
    log.error("Business exception: {}", ex.getMessage(), ex);
    ErrorCode errorCode = ex.getErrorCode();
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            errorCode.getHttpStatus().value(),
            errorCode.getHttpStatus().getReasonPhrase(),
            errorCode.getCode(),
            ex.getMessage(),
            request.getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, errorCode.getHttpStatus());
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ErrorResponse> handleValidationException(ValidationException ex, HttpServletRequest request) {
    log.error("Validation exception: {}", ex.getMessage(), ex);
    List<ErrorResponse.FieldError> fieldErrors = ex.getValidationErrors().stream()
            .map(error -> new ErrorResponse.FieldError("", error, null))
            .collect(Collectors.toList());

    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.VALIDATION_ERROR.getCode(),
            "Validation failed",
            request.getRequestURI(),
            fieldErrors
    );
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex, HttpServletRequest request) {
    log.error("Authentication exception: {}", ex.getMessage(), ex);
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.UNAUTHORIZED.value(),
            HttpStatus.UNAUTHORIZED.getReasonPhrase(),
            ErrorCode.UNAUTHORIZED.getCode(),
            ex.getMessage(),
            request.getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ErrorResponse> handleBadCredentialsException(BadCredentialsException ex, HttpServletRequest request) {
    log.error("Bad credentials: {}", ex.getMessage(), ex);
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.UNAUTHORIZED.value(),
            HttpStatus.UNAUTHORIZED.getReasonPhrase(),
            ErrorCode.INVALID_CREDENTIALS.getCode(),
            "Invalid username or password",
            request.getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, HttpStatus.UNAUTHORIZED);
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDeniedException(AccessDeniedException ex, HttpServletRequest request) {
    log.error("Access denied: {}", ex.getMessage(), ex);
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.FORBIDDEN.value(),
            HttpStatus.FORBIDDEN.getReasonPhrase(),
            ErrorCode.ACCESS_DENIED.getCode(),
            "You don't have permission to access this resource",
            request.getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, HttpStatus.FORBIDDEN);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex, HttpServletRequest request) {
    log.error("Constraint violation: {}", ex.getMessage(), ex);

    List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
            .map(violation -> new ErrorResponse.FieldError(
                    violation.getPropertyPath().toString(),
                    violation.getMessage(),
                    null
            ))
            .collect(Collectors.toList());

    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.VALIDATION_ERROR.getCode(),
            "Validation failed",
            request.getRequestURI(),
            fieldErrors
    );

    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleAllUncaughtException(Exception ex, HttpServletRequest request) {
    log.error("Unhandled exception: {}", ex.getMessage(), ex);
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
            ErrorCode.INTERNAL_SERVER_ERROR.getCode(),
            "An unexpected error occurred",
            request.getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
          MethodArgumentNotValidException ex,
          HttpHeaders headers,
          HttpStatusCode status,
          WebRequest request) {

    log.error("Method argument not valid: {}", ex.getMessage(), ex);

    List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult()
            .getFieldErrors()
            .stream()
            .map(fieldError -> new ErrorResponse.FieldError(
                    fieldError.getField(),
                    fieldError.getDefaultMessage() != null ? fieldError.getDefaultMessage() : "",
                    fieldError.getRejectedValue()
            ))
            .collect(Collectors.toList());

    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.VALIDATION_ERROR.getCode(),
            "Validation failed",
            ((org.springframework.http.server.ServletServerHttpRequest) request).getServletRequest().getRequestURI(),
            fieldErrors
    );

    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
          HttpMessageNotReadableException ex,
          HttpHeaders headers,
          HttpStatusCode status,
          WebRequest request) {

    log.error("Message not readable: {}", ex.getMessage(), ex);
    ErrorResponse response = new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            HttpStatus.BAD_REQUEST.getReasonPhrase(),
            ErrorCode.INVALID_INPUT.getCode(),
            "Malformed JSON request",
            ((org.springframework.http.server.ServletServerHttpRequest) request).getServletRequest().getRequestURI(),
            null
    );
    return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
  }
}
