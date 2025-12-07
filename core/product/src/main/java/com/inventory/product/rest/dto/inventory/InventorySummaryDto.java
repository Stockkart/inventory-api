package com.inventory.product.rest.dto.inventory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventorySummaryDto {
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

