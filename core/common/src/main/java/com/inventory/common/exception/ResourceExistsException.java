package com.inventory.common.exception;

import com.inventory.common.constants.ErrorCode;

/**
 * Exception thrown when an attempt is made to create a resource that already exists.
 */
public class ResourceExistsException extends BaseException {

    public ResourceExistsException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, message);
    }

    public ResourceExistsException(String resourceName, String fieldName, Object fieldValue) {
        super(
            ErrorCode.DUPLICATE_RESOURCE,
            String.format("%s already exists with %s: %s", resourceName, fieldName, fieldValue)
        );
    }
}
