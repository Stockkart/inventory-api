package com.inventory.common.constants;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Enum containing all error codes and their corresponding HTTP status codes and messages.
 */
@Getter
public enum ErrorCode {
  // Common errors (1000-1999)
  INTERNAL_SERVER_ERROR(1000, "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
  INVALID_INPUT(1001, "Invalid input provided", HttpStatus.BAD_REQUEST),
  RESOURCE_NOT_FOUND(1002, "The requested resource was not found", HttpStatus.NOT_FOUND),
  ACCESS_DENIED(1003, "Access denied", HttpStatus.FORBIDDEN),
  UNAUTHORIZED(1004, "Authentication required", HttpStatus.UNAUTHORIZED),
  METHOD_NOT_ALLOWED(1005, "Method not allowed", HttpStatus.METHOD_NOT_ALLOWED),
  VALIDATION_ERROR(1006, "Validation error", HttpStatus.BAD_REQUEST),
  DUPLICATE_RESOURCE(1007, "Resource already exists", HttpStatus.CONFLICT),
  /** Overloaded for a moment (e.g. no free database connection within mongo.pool.max-wait-ms). */
  SERVICE_BUSY(1008, "The server is busy, please try again in a moment", HttpStatus.SERVICE_UNAVAILABLE),

  // Business errors (2000-2999)
  BUSINESS_VALIDATION_ERROR(2000, "Business validation error", HttpStatus.BAD_REQUEST),

  // Product related errors (3000-3999)
  PRODUCT_NOT_FOUND(3000, "Product not found", HttpStatus.NOT_FOUND),
  INSUFFICIENT_STOCK(3001, "Insufficient stock available", HttpStatus.BAD_REQUEST),

  // User related errors (4000-4999)
  USER_NOT_FOUND(4000, "User not found", HttpStatus.NOT_FOUND),
  INVALID_CREDENTIALS(4001, "Invalid credentials", HttpStatus.UNAUTHORIZED),
  USER_ALREADY_EXISTS(4002, "User already exists", HttpStatus.CONFLICT),
  ACCOUNT_DISABLED(4003, "Account is disabled", HttpStatus.UNAUTHORIZED), // 401 Unauthorized is more appropriate than 403 Forbidden for login attempts

  // Order related errors (5000-5999)
  ORDER_NOT_FOUND(5000, "Order not found", HttpStatus.NOT_FOUND),
  INVALID_ORDER_STATUS(5001, "Invalid order status", HttpStatus.BAD_REQUEST),

  // Plan / subscription errors (6000-6999)
  PLAN_EXPIRED(6000, "Plan or trial has expired", HttpStatus.PAYMENT_REQUIRED),

  // Taxation / GST errors (7000-7999)
  GST_CONFIGURATION_MISSING(7000, "GST configuration is incomplete",
      HttpStatus.UNPROCESSABLE_ENTITY),

  // HR errors (8000-8099). Messages are plain sentences and never carry amounts, account
  // numbers or UPI ids, because GlobalExceptionHandler logs them.
  HR_NOT_LINKED(8000, "Your login is not linked to an employee in this shop",
      HttpStatus.NOT_FOUND),
  HR_EMPLOYEE_CODE_TAKEN(8001, "This employee code is already used in this shop",
      HttpStatus.CONFLICT),
  HR_LOGIN_ALREADY_LINKED(8002, "This login is already linked to another employee",
      HttpStatus.CONFLICT),
  HR_PERIOD_LOCKED(8003, "Payroll for this month is finalised, so it cannot be changed",
      HttpStatus.CONFLICT),
  HR_UNMARKED_DAYS(8004, "Some employees have unmarked days in this month",
      HttpStatus.UNPROCESSABLE_ENTITY),
  HR_RUN_STATE_CONFLICT(8005, "This payroll run cannot do that in its current state",
      HttpStatus.CONFLICT),
  HR_RUN_HAS_PAYMENTS(8006, "Undo the salary payments of this run first", HttpStatus.CONFLICT),
  HR_LEAVE_OVERLAP(8007, "This leave overlaps another leave request", HttpStatus.CONFLICT),
  HR_LEAVE_BALANCE_EXCEEDED(8008, "Not enough leave balance for this leave type",
      HttpStatus.UNPROCESSABLE_ENTITY),
  HR_LEAVE_STATE_CONFLICT(8009, "This leave request has already been decided",
      HttpStatus.CONFLICT),
  HR_ADVANCE_HAS_RECOVERIES(8010, "Part of this advance has already been recovered",
      HttpStatus.CONFLICT),
  HR_EMPLOYEE_NOT_EMPLOYED(8011, "The employee was not employed on this date",
      HttpStatus.UNPROCESSABLE_ENTITY),
  HR_NO_EMAIL(8012, "This employee has no email address", HttpStatus.UNPROCESSABLE_ENTITY),
  HR_CONCURRENT_UPDATE(8013, "Someone else changed this record. Reload and try again",
      HttpStatus.CONFLICT),
  HR_DAY_HAS_LEAVE(8014, "Change or cancel the leave first", HttpStatus.CONFLICT),
  HR_RUN_ORDER(8015, "Payroll months must be finalised oldest first and reopened newest first",
      HttpStatus.CONFLICT),
  HR_QUERY_TOO_SLOW(8016,
      "This HR screen took too long to load. Please try again in a minute.",
      HttpStatus.SERVICE_UNAVAILABLE),
  /** A system account code (1250/2400/5700) is taken by the shop's own account. */
  HR_ACCOUNT_CONFLICT(8017,
      "An account in your chart of accounts uses a code payroll needs",
      HttpStatus.CONFLICT),
  /** Same 400 the cap returned before it had its own code. */
  HR_ACTIVE_EMPLOYEE_LIMIT(8018, "A shop can have at most 1,000 active employees",
      HttpStatus.BAD_REQUEST);

  private final int code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(int code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }
}
