package com.inventory.pluginengine.policy;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Profile-agnostic inventory create validation input (mapped from API request in product module).
 */
@Value
@Builder
public class InventoryCreateValidationInput {
  String name;
  Integer count;
  String batchNo;
  Instant expiryDate;
  String companyName;
  String location;
  BigDecimal maximumRetailPrice;
  BigDecimal costPrice;
  BigDecimal priceToRetail;
  String schemeType;
  Integer scheme;
  Integer schemePayFor;
  Integer schemeFree;
  BigDecimal schemePercentage;
  String itemType;
  Integer itemTypeDegree;
  String billingMode;
  String baseUnit;
  boolean hasUnitConversion;
  Integer unitConversionFactor;
  String unitConversionUnit;
  String sgst;
  String cgst;
  Instant purchaseDate;
}
