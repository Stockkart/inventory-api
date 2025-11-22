package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;
import jakarta.validation.ConstraintViolation;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Exception thrown when validation fails.
 */
public class ValidationException extends BaseException {
    private final Set<String> validationErrors;

    public ValidationException(String message) {
        super(ErrorCode.VALIDATION_ERROR, message);
        this.validationErrors = Collections.singleton(message);
    }

    public ValidationException(Set<String> validationErrors) {
        super(ErrorCode.VALIDATION_ERROR, "Validation failed");
        this.validationErrors = validationErrors != null ? validationErrors : Collections.emptySet();
    }

    public static <T> ValidationException fromViolations(Set<ConstraintViolation<T>> violations) {
        Set<String> errors = violations.stream()
                .map(violation -> String.format("%s: %s", 
                    violation.getPropertyPath().toString(), 
                    violation.getMessage()))
                .collect(Collectors.toSet());
        return new ValidationException(errors);
    }

    public Set<String> getValidationErrors() {
        return validationErrors;
    }
}
