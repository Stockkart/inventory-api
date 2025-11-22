package com.inventory.product.rest.mapper;

import com.inventory.product.domain.model.Inventory;
import com.inventory.product.domain.model.Product;
import com.inventory.product.rest.dto.inventory.InventoryDetailResponse;
import com.inventory.product.rest.dto.inventory.InventoryReceiptResponse;
import com.inventory.product.rest.dto.inventory.InventorySummaryDto;
import com.inventory.product.rest.dto.inventory.ReceiveInventoryRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.Instant;

@Mapper(componentModel = "spring")
public interface InventoryMapper {

  @Mapping(target = "lotId", expression = "java(\"lot-\" + java.util.UUID.randomUUID().toString())")
  @Mapping(target = "productId", source = "barcode")
  @Mapping(target = "receivedCount", source = "count")
  @Mapping(target = "soldCount", constant = "0")
  @Mapping(target = "currentCount", source = "count")
  @Mapping(target = "receivedDate", expression = "java(java.time.Instant.now())")
  @Mapping(target = "createdAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "updatedAt", expression = "java(java.time.Instant.now())")
  @Mapping(target = "reminderConfig", ignore = true)
    // Will be set separately if needed
  Inventory toEntity(ReceiveInventoryRequest request);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "productId", source = "productId")
  InventoryReceiptResponse toReceiptResponse(Inventory inventory);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "productId", source = "productId")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
  InventorySummaryDto toSummary(Inventory inventory);

  @Mapping(target = "lotId", source = "lotId")
  @Mapping(target = "productId", source = "productId")
  @Mapping(target = "receivedCount", source = "receivedCount")
  @Mapping(target = "soldCount", source = "soldCount")
  @Mapping(target = "currentCount", source = "currentCount")
  @Mapping(target = "location", source = "location")
  @Mapping(target = "expiryDate", source = "expiryDate")
  @Mapping(target = "shopId", source = "shopId")
  InventoryDetailResponse toDetail(Inventory inventory);

  default Product createProductFromRequest(ReceiveInventoryRequest request) {
    if (request == null) {
      return null;
    }
    Product product = new Product();
    product.setBarcode(request.getBarcode());
    product.setName(request.getName() != null ? request.getName().trim() : "");
    product.setPrice(request.getPrice());
    product.setCreatedAt(Instant.now());
    product.setUpdatedAt(Instant.now());
    return product;
  }
}
