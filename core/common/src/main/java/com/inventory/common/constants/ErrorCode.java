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
  RATE_RULE_INVALID(3002, "Invalid rate rule", HttpStatus.BAD_REQUEST),
  RATE_RULE_BASE_NOT_ALLOWED(3003, "Rate rule base not allowed for this shop type", HttpStatus.BAD_REQUEST),
  RATE_RULES_CONFLICT(3004, "Rate rules were changed at the same time", HttpStatus.CONFLICT),

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
      HttpStatus.BAD_REQUEST),
  /** Self check-in is switched off in HR settings. */
  HR_SELF_CHECKIN_OFF(8019, "Self check-in is turned off for this shop", HttpStatus.CONFLICT),
  /** Location check, BLOCK: farther than the shop radius, or no location given. */
  HR_OUTSIDE_SHOP(8020, "You're not at the shop", HttpStatus.UNPROCESSABLE_ENTITY),
  /** Location check, BLOCK: the phone reported an accuracy worse than 200 m (or none). */
  HR_LOCATION_UNCLEAR(8021, "Location is not clear, please try again near a window",
      HttpStatus.UNPROCESSABLE_ENTITY),

  // Attendance app (biometric) 8022–8034. The app shows these word for word; the ones a worker
  // cannot fix themselves end with "Ask your manager to mark your attendance." (R7.10).
  /** Biometric app check-in is switched off in HR settings (R1.4). */
  HR_APP_OFF(8022,
      "Your shop has not turned on the Attendance app. Ask your manager to mark your attendance.",
      HttpStatus.CONFLICT),
  /** The pairing is unpaired, replaced or unknown; the app wipes itself on this (R3.2). */
  HR_APP_NOT_PAIRED(8023, "This phone is no longer paired. Ask your manager.",
      HttpStatus.FORBIDDEN),
  /** Signature, envelope or replay-with-changes failure; counted as a security signal (R5.8). */
  HR_APP_NOT_VERIFIED(8024,
      "This phone could not be verified. Ask your manager to re-pair it. "
          + "Ask your manager to mark your attendance.",
      HttpStatus.UNPROCESSABLE_ENTITY),
  /** Offline punch whose phone time is more than 24 h before it arrived (R6.11). */
  HR_APP_TOO_LATE(8025,
      "Too late to send this check-in. Ask your manager to mark your attendance.",
      HttpStatus.UNPROCESSABLE_ENTITY),
  /** Offline punch whose phone time is more than 5 min ahead of the server (R6.12). */
  HR_APP_CLOCK_WRONG(8026,
      "Your phone's clock is wrong. Fix the time, then ask your manager to mark your attendance.",
      HttpStatus.UNPROCESSABLE_ENTITY),
  /** Location check, BLOCK: the phone reported a mock (faked) location (R4.6). */
  HR_FAKE_LOCATION(8027, "Fake location detected", HttpStatus.UNPROCESSABLE_ENTITY),
  /** Over 20 punches per pairing per minute, or over 10 attempts on one code (R9.9, R9.10). */
  HR_TOO_MANY_ATTEMPTS(8028, "Too many attempts, wait a minute", HttpStatus.TOO_MANY_REQUESTS),
  /** Pairing code older than 15 minutes (R2.14). */
  HR_PAIRING_CODE_EXPIRED(8029, "This code has expired. Ask your manager for a new one.",
      HttpStatus.GONE),
  /** Pairing code already completed a pairing (R2.5, R2.14). */
  HR_PAIRING_CODE_USED(8030, "This code has already been used.", HttpStatus.CONFLICT),
  /** Pairing code cancelled by a newer code, "No" or "Don't agree" (R2.4, R2.14). */
  HR_PAIRING_CODE_CANCELLED(8031, "This code was cancelled. Ask your manager for a new one.",
      HttpStatus.CONFLICT),
  /** No code matches the 8 characters (R2.14). */
  HR_PAIRING_CODE_WRONG(8032, "This code is not right. Check the 8 characters and try again.",
      HttpStatus.NOT_FOUND),
  /** The employee of the code is EXITED (R2.14). */
  HR_PAIRING_EMPLOYEE_EXITED(8033,
      "This employee has left the shop, so the phone cannot be paired.",
      HttpStatus.UNPROCESSABLE_ENTITY),
  /** 5 refused codes from one installation in 15 minutes; %d = minutes to wait (R2.5). */
  HR_PAIRING_CODE_LOCKED(8034, "Too many wrong codes. Wait %d minutes and try again.",
      HttpStatus.TOO_MANY_REQUESTS);

  private final int code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(int code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }
}
