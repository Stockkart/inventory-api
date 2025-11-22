package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;

/**
 * Exception thrown when authentication fails due to invalid credentials or other authentication-related issues.
 */
public class AuthenticationException extends BaseException {
    
    /**
     * Constructs a new AuthenticationException with the specified detail message.
     *
     * @param message the detail message
     */
    public AuthenticationException(String message) {
        super(ErrorCode.INVALID_CREDENTIALS, message);
    }
    
    /**
     * Constructs a new AuthenticationException with the specified error code and detail message.
     *
     * @param errorCode the error code
     * @param message   the detail message
     */
    public AuthenticationException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
    
    /**
     * Constructs a new AuthenticationException with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public AuthenticationException(String message, Throwable cause) {
        super(ErrorCode.INVALID_CREDENTIALS, message, cause);
    }
    
    /**
     * Constructs a new AuthenticationException with the specified error code, detail message, and cause.
     *
     * @param errorCode the error code
     * @param message   the detail message
     * @param cause     the cause
     */
    public AuthenticationException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }
}
