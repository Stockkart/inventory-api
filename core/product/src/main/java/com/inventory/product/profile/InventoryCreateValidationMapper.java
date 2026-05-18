package com.inventory.product.profile;

import com.inventory.product.domain.model.enums.SchemeType;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.pluginengine.policy.InventoryCreateValidationInput;
import org.springframework.stereotype.Component;

@Component
public class InventoryCreateValidationMapper {

  public InventoryCreateValidationInput toInput(CreateInventoryRequest request) {
    boolean hasConversion = request.getUnitConversions() != null;
    Integer factor = hasConversion ? request.getUnitConversions().getFactor() : null;
    String conversionUnit = hasConversion ? request.getUnitConversions().getUnit() : null;

    return InventoryCreateValidationInput.builder()
        .name(request.getName())
        .count(request.getCount())
        .batchNo(request.getBatchNo())
        .expiryDate(request.getExpiryDate())
        .companyName(request.getCompanyName())
        .location(request.getLocation())
        .maximumRetailPrice(request.getMaximumRetailPrice())
        .costPrice(request.getCostPrice())
        .priceToRetail(request.getPriceToRetail())
        .schemeType(request.getSchemeType() != null ? request.getSchemeType().name() : null)
        .scheme(request.getScheme())
        .schemePayFor(request.getSchemePayFor())
        .schemeFree(request.getSchemeFree())
        .schemePercentage(request.getSchemePercentage())
        .itemType(request.getItemType() != null ? request.getItemType().name() : null)
        .itemTypeDegree(request.getItemTypeDegree())
        .billingMode(request.getBillingMode() != null ? request.getBillingMode().name() : null)
        .baseUnit(request.getBaseUnit())
        .hasUnitConversion(hasConversion)
        .unitConversionFactor(factor)
        .unitConversionUnit(conversionUnit)
        .sgst(request.getSgst())
        .cgst(request.getCgst())
        .purchaseDate(request.getPurchaseDate())
        .build();
  }
}
