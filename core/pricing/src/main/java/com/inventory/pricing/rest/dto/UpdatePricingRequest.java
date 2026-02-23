package com.inventory.pricing.rest.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for partial update of pricing data.
 * Only provided fields are updated.
 */
@Data
public class UpdatePricingRequest {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
