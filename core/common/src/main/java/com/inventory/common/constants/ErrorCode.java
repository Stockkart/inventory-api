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
  TOO_MANY_REQUESTS(1008, "Too many requests, try again shortly", HttpStatus.TOO_MANY_REQUESTS),

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
  IDEMPOTENCY_KEY_REUSED(5002, "Idempotency key was already used for a different request", HttpStatus.CONFLICT),

  // Plan / subscription errors (6000-6999)
  PLAN_EXPIRED(6000, "Plan or trial has expired", HttpStatus.PAYMENT_REQUIRED),
  FEATURE_NOT_IN_PLAN(6001, "Feature is not included in the current plan", HttpStatus.PAYMENT_REQUIRED),
  SEAT_LIMIT_REACHED(6002, "User seat limit reached for the current plan", HttpStatus.PAYMENT_REQUIRED),
  OCR_QUOTA_EXCEEDED(6003, "OCR quota exhausted for the current period", HttpStatus.PAYMENT_REQUIRED),
  VOUCHER_REJECTED(6010, "Voucher cannot be applied", HttpStatus.BAD_REQUEST),
  WALLET_BALANCE_CHANGED(6011, "Wallet balance changed; refresh the quote", HttpStatus.CONFLICT),
  ORDER_NOT_REFUNDABLE(6012, "This order cannot be refunded in its current state", HttpStatus.CONFLICT);

  private final int code;
  private final String message;
  private final HttpStatus httpStatus;

  ErrorCode(int code, String message, HttpStatus httpStatus) {
    this.code = code;
    this.message = message;
    this.httpStatus = httpStatus;
  }
}
