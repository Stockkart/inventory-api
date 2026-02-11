package com.inventory.product.rest.dto.inventory;

import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
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
  BigDecimal additionalDiscount;
  Integer receivedCount;
  Integer thresholdCount;
  Integer soldCount;
  Integer currentCount;
  String location;
  Instant expiryDate;
  String shopId;
  String vendorId;
  ItemType itemType;
  Integer itemTypeDegree;
  DiscountApplicable discountApplicable;
  Instant purchaseDate;
  String hsn;
  String batchNo;
  SchemeType schemeType;
  Integer scheme;
  BigDecimal schemePercentage;
  String sgst; // SGST rate (e.g., "9" for 9%)
  String cgst; // CGST rate (e.g., "9" for 9%)
  Instant createdAt;
}

