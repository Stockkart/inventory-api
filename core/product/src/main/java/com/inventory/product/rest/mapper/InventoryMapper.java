package com.inventory.product.rest.mapper;

import com.inventory.notifications.rest.dto.CreateReminderForInventoryRequest;
import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryListResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import java.util.List;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface InventoryMapper {

  // MongoDB will auto-generate the lotId as ObjectId
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
  default InventoryListResponse toInventoryListResponse(List<InventorySummaryDto> data) {
    InventoryListResponse response = new InventoryListResponse();
    response.setData(data);
    response.setMeta(null);
    return response;
  }

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
}
