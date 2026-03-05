package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.BillingMode;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.UpdateInventoryRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

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
    if (request.getSchemeType() == SchemeType.PERCENTAGE) {
      if (request.getSchemePercentage() == null) {
        throw new ValidationException("When schemeType is PERCENTAGE, schemePercentage is required");
      }
      if (request.getSchemePercentage().compareTo(BigDecimal.ZERO) < 0
          || request.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage must be between 0 and 100 (inclusive)");
      }
    } else if (request.getScheme() != null && request.getScheme() < 0) {
      throw new ValidationException("Scheme (free units) must be zero or greater");
    }
    if (request.getItemType() == ItemType.DEGREE
        && (request.getItemTypeDegree() == null || request.getItemTypeDegree() <= 0)) {
      throw new ValidationException("When itemType is DEGREE, itemTypeDegree must be present and greater than zero");
    }
    if (request.getPurchaseDate() != null) {
      validatePurchaseDate(request.getPurchaseDate());
    }
    validateTaxFieldsByBillingMode(request.getBillingMode(), request.getSgst(), request.getCgst());
    validateUnits(request.getBaseUnit(), request.getUnitConversions());
  }

  public void validateUpdateRequest(UpdateInventoryRequest request) {
    if (request == null) {
      return;
    }
    if (request.getItemType() == ItemType.DEGREE
        && (request.getItemTypeDegree() == null || request.getItemTypeDegree() <= 0)) {
      throw new ValidationException("When itemType is DEGREE, itemTypeDegree must be present and greater than zero");
    }
    if (request.getPurchaseDate() != null) {
      validatePurchaseDate(request.getPurchaseDate());
    }
    if (request.getSchemeType() == SchemeType.PERCENTAGE) {
      if (request.getSchemePercentage() == null) {
        throw new ValidationException("When schemeType is PERCENTAGE, schemePercentage is required");
      }
      if (request.getSchemePercentage().compareTo(BigDecimal.ZERO) < 0
          || request.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage must be between 0 and 100 (inclusive)");
      }
    } else if (request.getScheme() != null && request.getScheme() < 0) {
      throw new ValidationException("Scheme (free units) must be zero or greater");
    }
    if (request.getBillingMode() != null) {
      validateTaxFieldsByBillingMode(request.getBillingMode(), request.getSgst(), request.getCgst());
    }
    if (request.getBaseUnit() != null || request.getUnitConversions() != null) {
      validateUnits(request.getBaseUnit(), request.getUnitConversions());
    }
  }

  private void validateUnits(String baseUnit, UnitConversion unitConversions) {
    String effectiveBaseUnit = org.springframework.util.StringUtils.hasText(baseUnit)
        ? baseUnit.trim().toUpperCase()
        : "UNIT";
    validateUnitName(effectiveBaseUnit, "baseUnit");
    if (unitConversions == null) {
      return;
    }
    String unit = unitConversions.getUnit() != null ? unitConversions.getUnit().trim().toUpperCase() : null;
    validateUnitName(unit, "unitConversions.unit");
    if (effectiveBaseUnit.equals(unit)) {
      throw new ValidationException("unitConversions cannot include the baseUnit");
    }
    if (unitConversions.getFactor() == null || unitConversions.getFactor() <= 0) {
      throw new ValidationException("unitConversions.factor must be greater than zero for unit: " + unit);
    }
  }

  private void validateTaxFieldsByBillingMode(BillingMode billingMode, String sgst, String cgst) {
    BillingMode effectiveMode = billingMode != null ? billingMode : BillingMode.REGULAR;
    if (effectiveMode != BillingMode.BASIC) {
      return;
    }
    if (StringUtils.hasText(sgst) || StringUtils.hasText(cgst)) {
      throw new ValidationException("SGST/CGST must not be provided when billingMode is BASIC");
    }
  }

  private void validateUnitName(String unit, String fieldName) {
    if (!StringUtils.hasText(unit)) {
      throw new ValidationException(fieldName + " is required");
    }
    if (!unit.matches("^[A-Z0-9_ ]+$")) {
      throw new ValidationException(fieldName + " must contain only letters, digits, underscore and space");
    }
  }

  /**
   * Purchase date must be within 30 days in the past and not more than 30 days in the future.
   */
  private void validatePurchaseDate(Instant purchaseDate) {
    Instant now = Instant.now();
    Instant minAllowed = now.minus(30, ChronoUnit.DAYS);
    Instant maxAllowed = now.plus(30, ChronoUnit.DAYS);
    if (purchaseDate.isBefore(minAllowed)) {
      throw new ValidationException("Purchase date must not be older than 30 days");
    }
    if (purchaseDate.isAfter(maxAllowed)) {
      throw new ValidationException("Purchase date must not be more than 30 days in the future");
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
