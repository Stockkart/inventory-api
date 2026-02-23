package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Single item in bulk update: pricing ID + update payload.
 */
@Data
public class UpdateDefaultPriceItem {
  private String pricingId;
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private List<Rate> rates;
  private String defaultRate;
}
