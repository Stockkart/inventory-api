package com.inventory.product.validation;

import com.inventory.common.exception.ValidationException;
import com.inventory.pluginengine.policy.PolicyRegistry;
import com.inventory.pluginengine.profile.BusinessProfile;
import com.inventory.product.domain.model.enums.BillingMode;
import com.inventory.product.domain.model.enums.ItemType;
import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.profile.InventoryCreateValidationMapper;
import com.inventory.product.profile.ProfileResolver;
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

  private final ProfileResolver profileResolver;
  private final PolicyRegistry policyRegistry;
  private final InventoryCreateValidationMapper validationMapper;
  private final PackagingUnitService packagingUnitService;

  public void validateCreateRequest(CreateInventoryRequest request, String shopId) {
    validateShopId(shopId);
    if (request == null) {
      throw new ValidationException("Request cannot be null");
    }
    BusinessProfile profile = profileResolver.resolveForShop(shopId);
    String strategy = profile.strategy("inventoryValidator");
    if (!StringUtils.hasText(strategy)) {
      strategy = "pharmacyInventory";
    }
    policyRegistry.inventoryValidator(strategy)
        .validateCreate(validationMapper.toInput(request), profile);
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
    if (request.getBillingMode() != null) {
      validateTaxFieldsByBillingMode(request.getBillingMode(), request.getSgst(), request.getCgst());
    }
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

  private void validateTaxFieldsByBillingMode(BillingMode billingMode, String sgst, String cgst) {
    BillingMode effectiveMode = billingMode != null ? billingMode : BillingMode.REGULAR;
    if (effectiveMode != BillingMode.BASIC) {
      return;
    }
    if (StringUtils.hasText(sgst) || StringUtils.hasText(cgst)) {
      throw new ValidationException("SGST/CGST must not be provided when billingMode is BASIC");
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
