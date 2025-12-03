package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;

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

    validateReminderDates(request);
  }

  private void validateReminderDates(ReceiveInventoryRequest request) {
    Instant now = Instant.now();

    if (request.getReminderAt() != null) {
      if (request.getExpiryDate() == null) {
        throw new ValidationException("Expiry date is required when setting a standard expiry reminder");
      }
      if (request.getReminderAt().isBefore(now)) {
        throw new ValidationException("Standard reminder trigger time must be in the future");
      }
      if (request.getReminderAt().isAfter(request.getExpiryDate())) {
        throw new ValidationException("Standard reminder time cannot be after the product expiry date");
      }
    }

    if (request.getNewReminderAt() != null) {
      if (request.getNewReminderAt().isBefore(now)) {
        throw new ValidationException("Custom reminder trigger time must be in the future");
      }

      if (request.getReminderEndDate() != null) {
        if (request.getNewReminderAt().isAfter(request.getReminderEndDate())) {
          throw new ValidationException("Custom reminder trigger time cannot be after the reminder end date");
        }
      }

      else if (request.getExpiryDate() != null) {
        if (request.getNewReminderAt().isAfter(request.getExpiryDate())) {
          throw new ValidationException("Custom reminder trigger time cannot be after the product expiry date");
        }
      }
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
