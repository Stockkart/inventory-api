package com.inventory.product.mapper;

import com.inventory.notifications.rest.dto.request.CreateReminderForInventoryRequest;
import com.inventory.notifications.rest.dto.response.InventoryLowEventDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.UnitConversion;
import com.inventory.product.rest.dto.request.CreateInventoryRequest;
import com.inventory.product.rest.dto.response.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InventoryMapper {

  // lotId, receivedCount, currentCount set in service
  // Pricing: @Transient - set from request for create (AOP persistOnSave writes to Pricing); on read AOP enriches
  @Mapping(target = "lotId", ignore = true)
  @Mapping(target = "receivedCount", ignore = true)
  @Mapping(target = "receivedBaseCount", ignore = true)
  @Mapping(target = "soldCount", constant = "0")
  @Mapping(target = "soldBaseCount", constant = "0")
  @Mapping(target = "currentCount", ignore = true)
  @Mapping(target = "currentBaseCount", ignore = true)
  @Mapping(target = "receivedDate", expression = "java(java.time.Instant.now())")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  Inventory toEntity(CreateInventoryRequest request);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "barcode", source = "barcode")
  @Mapping(target = "reminderCreated", ignore = true)
  InventoryReceiptResponse toReceiptResponse(Inventory inventory);

  // Method to create InventoryReceiptResponse with reminderCreated flag
  default InventoryReceiptResponse toReceiptResponseWithReminder(Inventory inventory, boolean reminderCreated) {
    InventoryReceiptResponse response = toReceiptResponse(inventory);
    response.setReminderCreated(reminderCreated);
    return response;
  }

  // Method to create InventoryListResponse
  default InventoryListResponse toInventoryListResponse(List<InventorySummaryDto> data,  PageMeta page) {
    InventoryListResponse response = new InventoryListResponse();
    response.setData(data);
    response.setMeta(null);
    response.setPage(page);
    return response;
  }

  // Method to create InventoryListResponse
  default InventoryListResponse toInventoryListResponse(List<InventorySummaryDto> data) {
    InventoryListResponse response = new InventoryListResponse();
    response.setData(data);
    response.setMeta(null);
    return response;
  }

  @Mapping(target = "id", source = "id")
  @Mapping(target = "pricingId", source = "pricingId")
  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "barcode", source = "barcode")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "description", source = "description")
  @Mapping(target = "companyName", source = "companyName")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "additionalDiscount", source = "additionalDiscount")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "receivedBaseCount", expression = "java(resolveReceivedBaseCount(inventory))")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "soldBaseCount", expression = "java(resolveSoldBaseCount(inventory))")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "currentBaseCount", expression = "java(resolveCurrentBaseCount(inventory))")
  @Mapping(target = "location", source = "location")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "vendorId", source = "vendorId")
  @Mapping(target = "itemType", source = "itemType")
  @Mapping(target = "itemTypeDegree", source = "itemTypeDegree")
  @Mapping(target = "discountApplicable", source = "discountApplicable")
  @Mapping(target = "purchaseDate", source = "purchaseDate")
  @Mapping(target = "baseUnit", source = "baseUnit")
  @Mapping(target = "unitConversions", source = "unitConversions")
  @Mapping(target = "availableUnits", expression = "java(mapAvailableUnits(inventory))")
  @Mapping(target = "billingMode", source = "billingMode")
  @Mapping(target = "schemeType", source = "schemeType")
  @Mapping(target = "schemePercentage", source = "schemePercentage")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  @Mapping(target = "createdAt", source = "createdAt")
  InventorySummaryDto toSummary(Inventory inventory);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "pricingId", source = "pricingId")
  @Mapping(target = "barcode", source = "barcode")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "description", source = "description")
  @Mapping(target = "companyName", source = "companyName")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "priceToRetail", source = "priceToRetail")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "rates", source = "rates")
  @Mapping(target = "defaultRate", source = "defaultRate")
  @Mapping(target = "additionalDiscount", source = "additionalDiscount")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "receivedBaseCount", expression = "java(resolveReceivedBaseCount(inventory))")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "soldBaseCount", expression = "java(resolveSoldBaseCount(inventory))")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "currentBaseCount", expression = "java(resolveCurrentBaseCount(inventory))")
  @Mapping(target = "location", source = "location")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "itemType", source = "itemType")
  @Mapping(target = "itemTypeDegree", source = "itemTypeDegree")
  @Mapping(target = "discountApplicable", source = "discountApplicable")
  @Mapping(target = "purchaseDate", source = "purchaseDate")
  @Mapping(target = "baseUnit", source = "baseUnit")
  @Mapping(target = "unitConversions", source = "unitConversions")
  @Mapping(target = "availableUnits", expression = "java(mapAvailableUnits(inventory))")
  @Mapping(target = "billingMode", source = "billingMode")
  @Mapping(target = "schemeType", source = "schemeType")
  @Mapping(target = "schemePercentage", source = "schemePercentage")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  InventoryDetailResponse toDetail(Inventory inventory);

  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "inventoryId", source = "inventoryId")
  @Mapping(target = "expiryDate", source = "request.expiryDate")
  @Mapping(target = "reminderAt", source = "request.reminderAt")
  @Mapping(target = "customReminders", source = "request.customReminders")
  CreateReminderForInventoryRequest toCreateReminderForInventoryRequest(
      CreateInventoryRequest request,
      String shopId,
      String inventoryId
  );

  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "inventoryId", source = "inventoryId")
  @Mapping(target = "productName", source = "productName")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "threshold", source = "threshold")
  InventoryLowEventDto toNotificationEventDto(
    InventoryEventDto source
  );

  @Mapping(target = "shopId", source = "inventory.shopId")
  @Mapping(target = "inventoryId", source = "inventory.id")
  @Mapping(target = "productName", source = "inventory.name")
  @Mapping(target = "currentCount", expression = "java(resolveCurrentBaseCount(inventory))")
  @Mapping(target = "threshold", source = "threshold")
  InventoryEventDto toInventoryLowEventDto(
    Inventory inventory,
    Integer threshold
  );

  default List<AvailableUnitDto> mapAvailableUnits(Inventory inventory) {
    List<AvailableUnitDto> units = new ArrayList<>();
    if (inventory == null) {
      return units;
    }
    if (inventory.getBaseUnit() != null && !inventory.getBaseUnit().trim().isEmpty()) {
      units.add(new AvailableUnitDto(inventory.getBaseUnit().trim().toUpperCase(), true));
    }
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion != null && conversion.getUnit() != null && !conversion.getUnit().trim().isEmpty()) {
      String conversionUnit = conversion.getUnit().trim().toUpperCase();
      boolean alreadyPresent = units.stream().anyMatch(u -> conversionUnit.equals(u.getUnit()));
      if (!alreadyPresent) {
        units.add(new AvailableUnitDto(conversionUnit, false));
      }
    }
    return units;
  }

  default Integer resolveCurrentBaseCount(Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    if (inventory.getCurrentBaseCount() != null) {
      return inventory.getCurrentBaseCount();
    }
    if (inventory.getCurrentCount() == null) {
      return null;
    }
    int factor = resolveDisplayToBaseFactor(inventory);
    return inventory.getCurrentCount()
        .multiply(java.math.BigDecimal.valueOf(factor))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .intValue();
  }

  default Integer resolveSoldBaseCount(Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    if (inventory.getSoldBaseCount() != null) {
      return inventory.getSoldBaseCount();
    }
    if (inventory.getSoldCount() == null) {
      return null;
    }
    int factor = resolveDisplayToBaseFactor(inventory);
    return inventory.getSoldCount()
        .multiply(java.math.BigDecimal.valueOf(factor))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .intValue();
  }

  default Integer resolveReceivedBaseCount(Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    if (inventory.getReceivedBaseCount() != null) {
      return inventory.getReceivedBaseCount();
    }
    if (inventory.getReceivedCount() == null) {
      return null;
    }
    int factor = resolveDisplayToBaseFactor(inventory);
    return inventory.getReceivedCount()
        .multiply(java.math.BigDecimal.valueOf(factor))
        .setScale(0, java.math.RoundingMode.HALF_UP)
        .intValue();
  }

  default int resolveDisplayToBaseFactor(Inventory inventory) {
    UnitConversion conversion = inventory.getUnitConversions();
    if (conversion == null || conversion.getFactor() == null || conversion.getFactor() <= 0) {
      return 1;
    }
    return conversion.getFactor();
  }
}
