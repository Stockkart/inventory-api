package com.inventory.product.rest.mapper;

import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryEventDto;
import com.inventory.notifications.rest.dto.InventoryLowEventDto;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InventoryMapper {

  // lotId will be set in service before saving
  @Mapping(target = "lotId", ignore = true)
  @Mapping(target = "receivedCount", source = "count")
  @Mapping(target = "soldCount", constant = "0")
  @Mapping(target = "currentCount", source = "count")
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
  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "barcode", source = "barcode")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "description", source = "description")
  @Mapping(target = "companyName", source = "companyName")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "location", source = "location")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
  @Mapping(target = "vendorId", source = "vendorId")
  @Mapping(target = "sgst", source = "sgst")
  @Mapping(target = "cgst", source = "cgst")
  @Mapping(target = "createdAt", source = "createdAt")
  InventorySummaryDto toSummary(Inventory inventory);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "barcode", source = "barcode")
  @Mapping(target = "name", source = "name")
  @Mapping(target = "description", source = "description")
  @Mapping(target = "companyName", source = "companyName")
  @Mapping(target = "maximumRetailPrice", source = "maximumRetailPrice")
  @Mapping(target = "costPrice", source = "costPrice")
  @Mapping(target = "sellingPrice", source = "sellingPrice")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "location", source = "location")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
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
  @Mapping(target = "currentCount", source = "inventory.currentCount")
  @Mapping(target = "threshold", source = "threshold")
  InventoryEventDto toInventoryLowEventDto(
    Inventory inventory,
    Integer threshold
  );
}
