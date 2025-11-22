package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProductValidator {
    
    public void validateBarcode(String barcode) {
        if (!StringUtils.hasText(barcode)) {
            throw new ValidationException("Product barcode is required");
        }
    }
}
