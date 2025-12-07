package com.inventory.common.advice;

import com.inventory.common.constants.ErrorCode;
import com.inventory.common.dto.response.ApiError;
import com.inventory.common.dto.response.ApiErrorMapper;
import com.inventory.common.dto.response.ApiResponse;
import com.inventory.common.exception.BaseException;
import com.inventory.common.exception.ValidationException;
import org.springframework.beans.factory.annotation.Autowired;
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

  @ExceptionHandler(BaseException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleBaseException(BaseException ex, HttpServletRequest request) {
    log.error("Business exception: {}", ex.getMessage(), ex);
    ErrorCode errorCode = ex.getErrorCode();
    ApiError apiError = apiErrorMapper.toApiError(ex.getMessage(), errorCode.getHttpStatus().value());
    ApiResponse<ApiError> response = ApiResponse.error(ex.getMessage());
    response.setData(apiError);
    return new ResponseEntity<>(response, errorCode.getHttpStatus());
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<ApiResponse<ApiError>> handleValidationException(ValidationException ex, HttpServletRequest request) {
    log.error("Validation exception: {}", ex.getMessage(), ex);
    Map<String, String[]> errors = new HashMap<>();
    String[] errorMessages = ex.getValidationErrors().toArray(new String[0]);
    errors.put("general", errorMessages);

    ApiError apiError = apiErrorMapper.toApiError("Validation failed", HttpStatus.BAD_REQUEST.value(), errors);
    ApiResponse<ApiError> response = ApiResponse.error("Validation failed");
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
}
