package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

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
  private List<Rate> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
