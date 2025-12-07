package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.rest.dto.inventory.CreateInventoryRequest;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

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
  InventoryReceiptResponse toReceiptResponse(Inventory inventory);

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
}
