package com.inventory.plugins.medical.policy;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.policy.InventoryCreateValidationInput;
import com.inventory.pluginengine.policy.InventoryValidatorPolicy;
import com.inventory.pluginengine.profile.BusinessProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Pharmacy / medical shop inventory registration rules (batch, expiry, MRP/PTR/cost, schemes).
 */
@Component("pharmacyInventory")
public class PharmacyInventoryValidatorPolicy implements InventoryValidatorPolicy {

  @Override
  public void validateCreate(InventoryCreateValidationInput input, BusinessProfile profile) {
    if (input == null) {
      throw new ValidationException("Request cannot be null");
    }
    if (!StringUtils.hasText(input.getName())) {
      throw new ValidationException("Product name is required");
    }
    if (input.getCount() == null || input.getCount() <= 0) {
      throw new ValidationException("Count must be greater than zero");
    }
    if (!StringUtils.hasText(input.getCompanyName())) {
      throw new ValidationException("Company name is required");
    }
    if (!StringUtils.hasText(input.getLocation())) {
      throw new ValidationException("Location is required");
    }

    if (profile.isFieldRequired("inventory", "batchNo") && !StringUtils.hasText(input.getBatchNo())) {
      throw new ValidationException("Batch number is required");
    }
    if (profile.isFieldRequired("inventory", "expiryDate") && input.getExpiryDate() == null) {
      throw new ValidationException("Expiry date is required");
    }

    if (profile.isFieldRequired("inventory", "maximumRetailPrice")
        && (input.getMaximumRetailPrice() == null || input.getMaximumRetailPrice().signum() <= 0)) {
      throw new ValidationException("MRP is required and must be greater than zero");
    }
    if (profile.isFieldRequired("inventory", "priceToRetail")
        && (input.getPriceToRetail() == null || input.getPriceToRetail().signum() <= 0)) {
      throw new ValidationException("PTR is required and must be greater than zero");
    }
    if (profile.isFieldRequired("inventory", "costPrice")
        && (input.getCostPrice() == null || input.getCostPrice().signum() <= 0)) {
      throw new ValidationException("Cost price is required and must be greater than zero");
    }

    if (profile.isModuleEnabled("schemes")) {
      validateSchemes(input);
    }

    if ("DEGREE".equalsIgnoreCase(input.getItemType())) {
      if (input.getItemTypeDegree() == null || input.getItemTypeDegree() <= 0) {
        throw new ValidationException("When itemType is DEGREE, itemTypeDegree must be present and greater than zero");
      }
    }

    if (input.getPurchaseDate() != null) {
      validatePurchaseDate(input.getPurchaseDate());
    }

    validateTaxFieldsByBillingMode(input.getBillingMode(), input.getSgst(), input.getCgst());
    validateUnits(input);
  }

  private void validateSchemes(InventoryCreateValidationInput input) {
    if ("PERCENTAGE".equalsIgnoreCase(input.getSchemeType())) {
      if (input.getSchemePercentage() == null) {
        throw new ValidationException("When schemeType is PERCENTAGE, schemePercentage is required");
      }
      if (input.getSchemePercentage().compareTo(BigDecimal.ZERO) < 0
          || input.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage must be between 0 and 100 (inclusive)");
      }
    } else {
      boolean newStyle = input.getSchemePayFor() != null || input.getSchemeFree() != null;
      if (newStyle) {
        int payFor = input.getSchemePayFor() != null ? input.getSchemePayFor() : 0;
        int free = input.getSchemeFree() != null ? input.getSchemeFree() : 0;
        if (payFor < 0 || free < 0) {
          throw new ValidationException("schemePayFor and schemeFree must be zero or greater (e.g. 10 + 2)");
        }
      } else if (input.getScheme() != null && input.getScheme() < 0) {
        throw new ValidationException("Scheme (free units) must be zero or greater");
      }
    }
  }

  private void validateTaxFieldsByBillingMode(String billingMode, String sgst, String cgst) {
    String mode = billingMode != null ? billingMode : "REGULAR";
    if (!"BASIC".equalsIgnoreCase(mode)) {
      return;
    }
    if (StringUtils.hasText(sgst) || StringUtils.hasText(cgst)) {
      throw new ValidationException("SGST/CGST must not be provided when billingMode is BASIC");
    }
  }

  private void validateUnits(InventoryCreateValidationInput input) {
    String effectiveBaseUnit = StringUtils.hasText(input.getBaseUnit())
        ? input.getBaseUnit().trim().toUpperCase()
        : "UNIT";
    validateUnitName(effectiveBaseUnit, "baseUnit");
    if (!input.isHasUnitConversion()) {
      return;
    }
    String unit = input.getUnitConversionUnit() != null
        ? input.getUnitConversionUnit().trim().toUpperCase()
        : null;
    validateUnitName(unit, "unitConversions.unit");
    if (effectiveBaseUnit.equals(unit)) {
      throw new ValidationException("unitConversions cannot include the baseUnit");
    }
    if (input.getUnitConversionFactor() == null || input.getUnitConversionFactor() <= 0) {
      throw new ValidationException("unitConversions.factor must be greater than zero for unit: " + unit);
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
}
