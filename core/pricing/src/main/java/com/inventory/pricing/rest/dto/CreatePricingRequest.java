package com.inventory.pricing.rest.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for creating pricing data.
 * Used when inventory items are created (single or bulk).
 */
@Data
public class CreatePricingRequest {
  private String shopId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
