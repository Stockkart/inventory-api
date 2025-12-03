package com.inventory.product.rest.dto.inventory;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

@Value
@Builder
public class InventoryDetailResponse {
  String lotId;
  String barcode;
  String name;
  String description;
  String companyName;
  BigDecimal maximumRetailPrice;
  BigDecimal costPrice;
  BigDecimal sellingPrice;
  Integer receivedCount;
  Integer soldCount;
  Integer currentCount;
  String location;
  Instant expiryDate;
  String shopId;
}

