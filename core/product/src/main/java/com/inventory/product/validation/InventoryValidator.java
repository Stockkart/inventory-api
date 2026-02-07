package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class InventoryValidator {

  public void validateCreateRequest(CreateInventoryRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(request.getBarcode())) {
      throw new ValidationException("Barcode is required");
    }
    if (!StringUtils.hasText(request.getName())) {
      throw new ValidationException("Product name is required");
    }
    if (request.getCount() == null || request.getCount() <= 0) {
      throw new ValidationException("Count must be greater than zero");
    }
    if (request.getScheme() != null && request.getScheme() < 0) {
      throw new ValidationException("Scheme (free units) must be zero or greater");
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

  /**
   * Validate lotId format.
   * Allows alphanumeric characters, dashes, and underscores.
   * Minimum length: 3, Maximum length: 100
   *
   * @param lotId the lot ID to validate
   */
  public void validateLotIdFormat(String lotId) {
    if (!StringUtils.hasText(lotId)) {
      throw new ValidationException("Lot ID is required");
    }

    String trimmedLotId = lotId.trim();
    
    // Length validation
    if (trimmedLotId.length() < 3) {
      throw new ValidationException("Lot ID must be at least 3 characters long");
    }
    if (trimmedLotId.length() > 100) {
      throw new ValidationException("Lot ID must not exceed 100 characters");
    }

    // Format validation: alphanumeric, dashes, underscores only
    if (!trimmedLotId.matches("^[a-zA-Z0-9_-]+$")) {
      throw new ValidationException("Lot ID can only contain alphanumeric characters, dashes, and underscores");
    }
  }
}
