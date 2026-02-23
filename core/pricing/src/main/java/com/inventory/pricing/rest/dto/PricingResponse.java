package com.inventory.pricing.rest.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingResponse {
  private String id;
  private String shopId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal sellingPrice;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
  private Instant createdAt;
  private Instant updatedAt;
}
