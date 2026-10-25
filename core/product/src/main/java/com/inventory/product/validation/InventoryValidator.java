package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.request.UpdateInventoryRequest;
import com.inventory.product.service.PackagingUnitService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class InventoryValidator {

  /**
   * Auto-generated: {@code LOT-YYYYMMDDHHMMSS-XXXXXXXX}. User-provided: alphanumeric reference.
   */
  private static final Pattern LOT_ID_FORMAT = Pattern.compile(
      "^(LOT-\\d{14}-[A-Z0-9]{8}|[A-Za-z0-9][A-Za-z0-9._/-]{2,63})$");

  private final PackagingUnitService packagingUnitService;

  public void validateCreateRequest(CreateInventoryRequest request) {
    if (request == null) {
      throw new ValidationException("Request cannot be null");
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
      if (request.getSchemePercentage().compareTo(BigDecimal.valueOf(-100)) < 0
          || request.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage must be between -100 and 100 (inclusive)");
      }
    } else {
      boolean newStyle = request.getSchemePayFor() != null || request.getSchemeFree() != null;
      if (newStyle) {
        int payFor = request.getSchemePayFor() != null ? request.getSchemePayFor() : 0;
        int free = request.getSchemeFree() != null ? request.getSchemeFree() : 0;
        if (payFor < 0 || free < 0) {
          throw new ValidationException("schemePayFor and schemeFree must be zero or greater (e.g. 10 + 2)");
        }
      } else if (request.getScheme() != null && request.getScheme() < 0) {
        throw new ValidationException("Scheme (free units) must be zero or greater");
      }
    }
    if (request.getItemType() == ItemType.DEGREE
        && (request.getItemTypeDegree() == null || request.getItemTypeDegree() <= 0)) {
      throw new ValidationException("When itemType is DEGREE, itemTypeDegree must be present and greater than zero");
    }
    if (request.getPurchaseDate() != null) {
      validatePurchaseDate(request.getPurchaseDate());
    }
    validateTaxFieldsByBillingMode(request.getBillingMode(), request.getSgst(), request.getCgst());
    validatePackaging(request.getBaseUnit(), request.getUnitsPerPack(), request.getUnitConversions());
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
      validateEditedPurchaseDate(request.getPurchaseDate());
    }
    if (request.getSchemeType() == SchemeType.PERCENTAGE) {
      if (request.getSchemePercentage() == null) {
        throw new ValidationException("When schemeType is PERCENTAGE, schemePercentage is required");
      }
      if (request.getSchemePercentage().compareTo(BigDecimal.valueOf(-100)) < 0
          || request.getSchemePercentage().compareTo(BigDecimal.valueOf(100)) > 0) {
        throw new ValidationException("Scheme percentage must be between -100 and 100 (inclusive)");
      }
    } else {
      boolean newStyle = request.getSchemePayFor() != null || request.getSchemeFree() != null;
      if (newStyle) {
        int payFor = request.getSchemePayFor() != null ? request.getSchemePayFor() : 0;
        int free = request.getSchemeFree() != null ? request.getSchemeFree() : 0;
        if (payFor < 0 || free < 0) {
          throw new ValidationException("schemePayFor and schemeFree must be zero or greater");
        }
      } else if (request.getScheme() != null && request.getScheme() < 0) {
        throw new ValidationException("Scheme (free units) must be zero or greater");
      }
    }
    validateTaxFieldsOnUpdate(request.getBillingMode(), request.getSgst(), request.getCgst());
    if (request.getBaseUnit() != null || request.getUnitConversions() != null) {
      Integer unitsPerPack = request.getUnitConversions() != null
          ? request.getUnitConversions().getFactor()
          : null;
      validatePackaging(request.getBaseUnit(), unitsPerPack, request.getUnitConversions());
    }
  }

  private void validatePackaging(
      String baseUnit,
      Integer unitsPerPack,
      UnitConversion unitConversions) {
    String effectiveBase = StringUtils.hasText(baseUnit)
        ? PackagingUnitService.normalizeUqc(baseUnit)
        : "UNT";
    packagingUnitService.validateBaseUnit(effectiveBase);

    Integer effectiveUnitsPerPack = unitsPerPack;
    if ((effectiveUnitsPerPack == null || effectiveUnitsPerPack <= 0)
        && unitConversions != null
        && unitConversions.getFactor() != null
        && unitConversions.getFactor() > 0) {
      effectiveUnitsPerPack = unitConversions.getFactor();
    }
    packagingUnitService.validateUnitsPerPack(effectiveBase, effectiveUnitsPerPack);

    if (unitConversions != null && StringUtils.hasText(unitConversions.getUnit())) {
      String packUnit = unitConversions.getUnit().trim().toUpperCase();
      if (packUnit.equals(effectiveBase)) {
        throw new ValidationException("Pack unit cannot be the same as base UQC");
      }
      if (unitConversions.getFactor() == null || unitConversions.getFactor() <= 0) {
        throw new ValidationException("unitConversions.factor must be greater than zero");
      }
    }
  }

  static final String GST_REQUIRED =
      "SGST and CGST are required (enter 0 for a GST-exempt item)";

  /**
   * GST belongs to the lot; the shop has no rate to fall back on. A taxable (non-BASIC) lot must
   * state both SGST and CGST on create, 0 for an exempt item. A BASIC (estimate-only) lot carries
   * none.
   */
  private void validateTaxFieldsByBillingMode(BillingMode billingMode, String sgst, String cgst) {
    BillingMode effectiveMode = billingMode != null ? billingMode : BillingMode.REGULAR;
    if (effectiveMode != BillingMode.BASIC) {
      if (!StringUtils.hasText(sgst) || !StringUtils.hasText(cgst)) {
        throw new ValidationException(GST_REQUIRED);
      }
      return;
    }
    if (StringUtils.hasText(sgst) || StringUtils.hasText(cgst)) {
      throw new ValidationException("SGST/CGST must not be provided when billingMode is BASIC");
    }
  }

  /**
   * An update leaves GST alone unless it sends it; what it sends must not blank a taxable lot's
   * rate (BASIC lots carry none).
   */
  private void validateTaxFieldsOnUpdate(BillingMode billingMode, String sgst, String cgst) {
    if (billingMode == BillingMode.BASIC) {
      validateTaxFieldsByBillingMode(billingMode, sgst, cgst);
      return;
    }
    if ((sgst != null && !StringUtils.hasText(sgst)) || (cgst != null && !StringUtils.hasText(cgst))) {
      throw new ValidationException(GST_REQUIRED);
    }
  }

  /**
   * Purchase date must be within 30 days in the past and not more than 30 days in the future.
   */
  /**
   * Editing a lot corrects the day it was bought, which may be any day in the past. The date
   * arrives as the shop's local day at 00:00 UTC, so a day of slack keeps "today" valid in
   * time zones ahead of UTC.
   */
  private void validateEditedPurchaseDate(Instant purchaseDate) {
    if (purchaseDate.isAfter(Instant.now().plus(1, ChronoUnit.DAYS))) {
      throw new ValidationException("Purchase date cannot be in the future");
    }
  }

  private void validatePurchaseDate(Instant purchaseDate) {
    Instant now = Instant.now();
    Instant minAllowed = now.minus(30, ChronoUnit.DAYS);
    Instant maxAllowed = now.plus(30, ChronoUnit.DAYS);
    if (purchaseDate.isBefore(minAllowed)) {
      throw new ValidationException("Purchase date cannot be more than 30 days in the past");
    }
    if (purchaseDate.isAfter(maxAllowed)) {
      throw new ValidationException("Purchase date cannot be more than 30 days in the future");
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
    if (lotId.trim().length() > 128) {
      throw new ValidationException("Lot ID is too long");
    }
  }

  public void validateLotIdFormat(String lotId) {
    if (!StringUtils.hasText(lotId)) {
      throw new ValidationException("Lot ID is required");
    }
    String trimmed = lotId.trim();
    if (trimmed.length() > 64) {
      throw new ValidationException("Lot ID must be at most 64 characters");
    }
    if (!LOT_ID_FORMAT.matcher(trimmed).matches()) {
      throw new ValidationException(
          "Lot ID must be LOT-YYYYMMDDHHMMSS-XXXXXXXX or an alphanumeric reference (letters, numbers, ., _, /, -)");
    }
  }
}
