package com.inventory.pricing.rest.dto;

import com.inventory.pricing.domain.model.Rate;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request to update default/selling price. Only provided fields are updated.
 * Allowed fields: maximumRetailPrice, priceToRetail, rates, defaultRate.
 */
@Data
public class UpdateDefaultPriceRequest {
  private BigDecimal maximumRetailPrice;
  private BigDecimal priceToRetail;
  private List<Rate> rates;
  private String defaultRate;
}
