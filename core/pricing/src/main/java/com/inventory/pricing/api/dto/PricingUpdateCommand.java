package com.inventory.pricing.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Command to update pricing (partial). Only non-null fields are applied. API contract. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PricingUpdateCommand {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<RateDto> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
