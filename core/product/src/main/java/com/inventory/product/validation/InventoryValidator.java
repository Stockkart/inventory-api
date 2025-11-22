package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InventoryValidator {
    
    public void validateReceiveRequest(ReceiveInventoryRequest request) {
        if (request == null) {
            throw new ValidationException("Request cannot be null");
        }
        if (!StringUtils.hasText(request.getBarcode())) {
            throw new ValidationException("Product barcode is required");
        }
        if (request.getCount() <= 0) {
            throw new ValidationException("Count must be greater than zero");
        }
        if (!StringUtils.hasText(request.getShopId())) {
            throw new ValidationException("Shop ID is required");
        }
        if (!StringUtils.hasText(request.getUserId())) {
            throw new ValidationException("User ID is required");
        }
    }
    
    public void validateShopId(String shopId) {
        if (!StringUtils.hasText(shopId)) {
            throw new ValidationException("Shop ID is required");
        }
    }
    
    public void validateLotId(String lotId) {
        if (!StringUtils.hasText(lotId)) {
            throw new ValidationException("Lot ID is required");
        }
    }
}
