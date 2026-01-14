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
  String id;
  String lotId;
  String barcode;
  String name;
  String description;
  String companyName;
  BigDecimal maximumRetailPrice;
  BigDecimal costPrice;
  BigDecimal sellingPrice;
  Integer receivedCount;
  Integer thresholdCount;
  Integer soldCount;
  Integer currentCount;
  String location;
  Instant expiryDate;
  String shopId;
  String vendorId;
  String hsn;
  String batchNo;
  String scheme;
  String sgst; // SGST rate (e.g., "9" for 9%)
  String cgst; // CGST rate (e.g., "9" for 9%)
  Instant createdAt;
}

