package com.inventory.product.rest.dto.inventory;

import com.inventory.pricing.rest.dto.response.RateDto;
import com.inventory.product.domain.model.DiscountApplicable;
import com.inventory.product.domain.model.BillingMode;
import com.inventory.product.domain.model.ItemType;
import com.inventory.product.domain.model.SchemeType;
import com.inventory.product.domain.model.UnitConversion;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventorySummaryDto {
  String id;
  /** Pricing document ID. Null for legacy inventories without a pricing record. */
  String pricingId;
  String lotId;
  String barcode;
  String name;
  String description;
  String companyName;
  BigDecimal maximumRetailPrice;
  BigDecimal costPrice;
  /** Original Price to Retail (PTR). */
  BigDecimal priceToRetail;
  /** Effective selling price (from defaultRate). */
  BigDecimal sellingPrice;
  List<RateDto> rates;
  String defaultRate;
  BigDecimal additionalDiscount;
  BigDecimal receivedCount;
  Integer receivedBaseCount;
  Integer thresholdCount;
  BigDecimal soldCount;
  Integer soldBaseCount;
  BigDecimal currentCount;
  Integer currentBaseCount;
  String baseUnit;
  UnitConversion unitConversions;
  List<AvailableUnitDto> availableUnits;
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
  BillingMode billingMode;
  SchemeType schemeType;
  Integer scheme;
  BigDecimal schemePercentage;
  String sgst; // SGST rate (e.g., "9" for 9%)
  String cgst; // CGST rate (e.g., "9" for 9%)
  Instant createdAt;
}

