package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.business.CreateBusinessTypeRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BusinessTypeValidator {
    
    public void validateCreateRequest(CreateBusinessTypeRequest request) {
        if (request == null) {
            throw new ValidationException("Request cannot be null");
        }
        if (!StringUtils.hasText(request.getCode())) {
            throw new ValidationException("Business type code is required");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new ValidationException("Business type name is required");
        }
    }
}
