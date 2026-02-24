package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for partial update of pricing data.
 * Only provided fields are updated.
 */
@Data
public class UpdatePricingRequest {
  private BigDecimal maximumRetailPrice;
  private BigDecimal costPrice;
  private BigDecimal priceToRetail;
  private List<Rate> rates;
  private String defaultRate;
  private BigDecimal additionalDiscount;
  private String sgst;
  private String cgst;
}
